/*
 * Copyright (C) 2008 Alexander Christian <alex(at)root1.de>. All rights reserved.
 *
 * This file is part of SIMON.
 *
 *   SIMON is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   SIMON is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with SIMON.  If not, see <http://www.gnu.org/licenses/>.
 */
package host.anzo.simon;

import host.anzo.simon.codec.SimonProxyFilter;
import host.anzo.simon.codec.base.SimonProtocolCodecFactory;
import host.anzo.simon.exceptions.EstablishConnectionFailed;
import host.anzo.simon.ssl.SslContextFactory;
import host.anzo.simon.utils.FilterEntry;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.filterchain.DefaultIoFilterChainBuilder;
import org.apache.mina.core.future.CloseFuture;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.IoFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.service.IoConnector;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.logging.LoggingFilter;
import org.apache.mina.filter.ssl.SslFilter;
import org.apache.mina.transport.socket.nio.NioSocketConnector;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * @author ACHR
 */
@Slf4j
abstract class AbstractLookup implements Lookup {
	/**
	 * A relation map between server-connection-string and
	 * ClientToServerConnection objects.<br>
	 * This is used to re-use already existing connections.<br>
	 * This member has static access. So it's reachable from every lookup
	 * implementation class
	 */
	static final Map<String, ClientToServerConnection> serverDispatcherRelation = new HashMap<>();

	static final Monitor monitorCompleteShutdown = new Monitor();

	protected InetAddress sourceAddress;

	/**
	 * A simple container class that relates the dispatcher to a session
	 */
	record SessionDispatcherContainer(IoSession session, Dispatcher dispatcher) {
	}

	@Override
	public void setSourceAddress(InetAddress sourceAddress) {
		this.sourceAddress = sourceAddress;
	}

	@Override
	public boolean release(Object remoteObject) {
		log.debug("begin");

		if (remoteObject == null) {
			throw new IllegalArgumentException("the argument is not a releasable remote object. Given object is null");
		}

		// retrieve the proxy object
		SimonProxy proxy = Simon.getSimonProxy(remoteObject);

		if (!proxy.isRegularLookup()) {
			throw new IllegalArgumentException("Provided proxy is callback object and is not manually releasable. Please release your lookup'ed object(s) instead or wait for GC to release it.");
		}

		log.debug("releasing proxy {}", proxy.getDetailString());

		// release the proxy and get the related dispatcher
		Dispatcher dispatcher = proxy.getDispatcher();
		boolean result;
		if (dispatcher != null) {
			// get the list with listeners that have to be notified about the release and the followed close-event
			List<ClosedListener> removeClosedListenerList = dispatcher.removeClosedListenerList(proxy.getRemoteObjectName());

			proxy.release();

			if (removeClosedListenerList != null) {
				// forward the release event to all listeners
				for (ClosedListener closedListener : removeClosedListenerList) {
					closedListener.closed();
				}
				removeClosedListenerList.clear();
			}

			result = AbstractLookup.releaseDispatcher(dispatcher);
		} else {
			result = false;
		}
		log.debug("end");
		return result;
	}

	@Override
	public List<ClosedListener> getClosedListeners(Object remoteObject) {
		SimonProxy simonProxy = Simon.getSimonProxy(remoteObject);
		Dispatcher dispatcher = simonProxy.getDispatcher();
		return new ArrayList<>(dispatcher.getClosedListenerList(simonProxy.getRemoteObjectName()));
	}

	@Override
	public void addClosedListener(Object remoteObject, ClosedListener closedListener) {
		SimonProxy simonProxy = Simon.getSimonProxy(remoteObject);
		Dispatcher dispatcher = simonProxy.getDispatcher();
		dispatcher.addClosedListener(closedListener, simonProxy.getRemoteObjectName());
	}

	@Override
	public boolean removeClosedListener(Object remoteObject, ClosedListener closedListener) {
		SimonProxy simonProxy = Simon.getSimonProxy(remoteObject);
		Dispatcher dispatcher = simonProxy.getDispatcher();
		return dispatcher.removeClosedListener(closedListener, simonProxy.getRemoteObjectName());
	}

	/**
	 * Creates a unique string for a server by using the host and port
	 *
	 * @param host the servers host
	 * @param port the port the server listens on
	 * @return a server string
	 */
	String createServerString(@NotNull InetAddress host, int port) {
		return (sourceAddress != null ? sourceAddress.getHostAddress() + "@" : "") + host.getHostAddress() + ":" + port;
	}

	/**
	 * Creates a connection to the server and returns a container that holds the
	 * dispatcher session relation
	 *
	 * @param remoteObjectName  the remote object name
	 * @param serverAddress     the address of the server
	 * @param serverPort        the server registrys port
	 * @param sslContextFactory the used ssl context factory
	 * @param proxyConfig       the used proxy configuration
	 * @return a container with the created session and dispatcher
	 * @throws EstablishConnectionFailed if connection to server can't be
	 *                                   established
	 */
	SessionDispatcherContainer buildSessionDispatcherContainer(String remoteObjectName, InetAddress serverAddress, int serverPort, SslContextFactory sslContextFactory, SimonProxyConfig proxyConfig) throws EstablishConnectionFailed {

		Dispatcher dispatcher = null;
		IoSession session = null;

		String serverString = createServerString(serverAddress, serverPort);

		log.debug("check if serverstring '{}' is already in the serverDispatcherRelation list", serverString);

		synchronized (serverDispatcherRelation) {

			if (serverDispatcherRelation.containsKey(serverString)) {

				// retrieve the already stored connection
				ClientToServerConnection ctsc = serverDispatcherRelation.remove(serverString);
				ctsc.addRef();
				serverDispatcherRelation.put(serverString, ctsc);
				dispatcher = ctsc.getDispatcher();
				session = ctsc.getSession();
				log.debug("Got ClientToServerConnection from list");
			} else {

				log.debug("No ClientToServerConnection in list. Creating new one.");

				dispatcher = new Dispatcher(serverString, getClassLoader(), Simon.getThreadPool());

				// an executor service for handling the message reading in a threadpool
				ExecutorService filterchainWorkerPool = null;
				//                filterchainWorkerPool = new OrderedThreadPoolExecutor();

				IoConnector connector = new NioSocketConnector();
				connector.setHandler(dispatcher);

				/* ******************************************
				 * Setup filterchain before connecting to get all events like session created
				 * and session opened within the filters
				 */
				DefaultIoFilterChainBuilder filterChain = connector.getFilterChain();

				// create a list of used filters
				List<FilterEntry> filters = new ArrayList<>();

				// check for SSL
				if (sslContextFactory != null) {
					SSLContext context = sslContextFactory.getSslContext();

					if (context != null) {
						SslFilter sslFilter = new SslFilter(context);
						filters.add(new FilterEntry(sslFilter.getClass().getName(), sslFilter));
						log.debug("SSL ON");
					} else {
						log.warn("SSLContext retrieved from SslContextFactory was 'null', so starting WITHOUT SSL!");
					}
				}

				if (log.isTraceEnabled()) {
					filters.add(new FilterEntry(LoggingFilter.class.getName(), new LoggingFilter()));
				}

				// don't use a threading model on filter level
				//                filters.add(new FilterEntry(filterchainWorkerPool.getClass().getName(), new ExecutorFilter(filterchainWorkerPool)));
				// add the simon protocol
				SimonProtocolCodecFactory protocolFactory = null;
				try {

					protocolFactory = Utils.getProtocolFactoryInstance(Simon.getProtocolCodecFactory());
				} catch (ClassNotFoundException e) {
					log.error("ClassNotFoundException while preparing ProtocolFactory: {}", e.getMessage());
					throw new IllegalArgumentException(e);
				} catch (InstantiationException e) {
					log.error("InstantiationException while preparing ProtocolFactory: {}", e.getMessage());
					throw new IllegalArgumentException(e);
				} catch (IllegalAccessException e) {
					log.error("IllegalAccessException while preparing ProtocolFactory: {}", e.getMessage());
					throw new IllegalArgumentException(e);
				}

				protocolFactory.setup(false);
				filters.add(new FilterEntry(protocolFactory.getClass().getName(), new ProtocolCodecFilter(protocolFactory)));

				// setup for proxy connection if necessary
				String connectionTarget;
				if (proxyConfig != null) {

					// create the proxy filter with reference to the filter list
					// proxy filter will later on replace all proxy filters etc. with the ones from filter list
					connectionTarget = proxyConfig.toString();
					filterChain.addLast(SimonProxyFilter.class.getName(), new SimonProxyFilter(serverAddress.getHostName(), serverPort, proxyConfig, filters));
					log.trace("prepared for proxy connection. chain is now: {}", filterChain);
				} else {

					// add the filters from the list to the filter chain
					connectionTarget = "Connection[" + serverAddress + ":" + serverPort + "]";
					for (FilterEntry relation : filters) {
						filterChain.addLast(relation.name, relation.filter);
					}
				}
				log.debug("Using: {}", connectionTarget);

				// now we can try to connect ...
				ConnectFuture future = null;
				try {

					InetSocketAddress remote;

					// decide whether the connection goes via proxy or not
					if (proxyConfig == null) {
						remote = new InetSocketAddress(serverAddress, serverPort);
					} else {
						remote = new InetSocketAddress(proxyConfig.getProxyHost(), proxyConfig.getProxyPort());
					}

					if (sourceAddress != null) {
						future = connector.connect(remote, new InetSocketAddress(sourceAddress, 0 /* let oS decide on source port */));
					} else {
						future = connector.connect(remote); // let OS choose the source address
					}

					boolean finished = future.awaitUninterruptibly(Statics.DEFAULT_CONNECT_TIMEOUT);
					if (!finished) {
						log.debug("Connect timed out after {} ms", Statics.DEFAULT_CONNECT_TIMEOUT);
					}
				} catch (Exception e) {

					if (session != null) {
						log.trace("session != null. closing it...");
						session.closeNow();
					}
					connector.dispose();
					dispatcher.shutdown();
					if (filterchainWorkerPool != null) {
						filterchainWorkerPool.shutdown();
					}

					throw new EstablishConnectionFailed(
							"Exception occured while connection/getting session for " + connectionTarget + ".", e);
				}

				if (future.isConnected()) { // check if the connection succeeded

					session = future.getSession(); // this cannot return null, because we waited uninterruptibly for the connect-process
					log.trace("connected with {}. remoteObjectName={}", connectionTarget, remoteObjectName);
				} else {
					connector.dispose();
					dispatcher.shutdown();
					if (filterchainWorkerPool != null) {
						filterchainWorkerPool.shutdown();
					}
					throw new EstablishConnectionFailed("Could not establish connection to " + connectionTarget +
							". Maybe host or network is down?");
				}

				// configure the session
				session.getConfig().setIdleTime(IdleStatus.BOTH_IDLE, Statics.DEFAULT_IDLE_TIME);
				session.getConfig().setWriteTimeout(Statics.DEFAULT_WRITE_TIMEOUT);

				// store this connection for later re-use
				ClientToServerConnection ctsc = new ClientToServerConnection(serverString, dispatcher, session, connector, filterchainWorkerPool);
				ctsc.addRef();
				serverDispatcherRelation.put(serverString, ctsc);
				monitorCompleteShutdown.reset();
			}
		}

		return new SessionDispatcherContainer(session, dispatcher);
	}

	/**
	 * Awaits a complete network shutdown. Means: Waits until all network
	 * connections are closed or timeout occurs.
	 *
	 * @param timeout timeout for awaiting complete network shutdown
	 * @since 1.2.0
	 */
	public void awaitCompleteShutdown(long timeout) {
		monitorCompleteShutdown.waitForSignal(timeout);
	}

	/**
	 * Releases a {@link Dispatcher}. If there is no more server string
	 * referencing the Dispatcher, the Dispatcher will be released/shutdown.
	 *
	 * @param dispatcher the iDispatcher to release
	 * @return true if the Dispatcher is shut down, false if there's still a
	 * reference pending
	 */
	protected static boolean releaseDispatcher(Dispatcher dispatcher) {

		boolean result = false;

		synchronized (serverDispatcherRelation) {

			// get the serverstring the dispatcher is connected to
			String serverString = dispatcher.getServerString();

			// if there's an instance of this connection known ...
			if (serverDispatcherRelation.containsKey(serverString)) {

				// ... remove the connection from the list ...
				final ClientToServerConnection ctsc = serverDispatcherRelation.remove(serverString);
				int refCount = ctsc.delRef();

				log.trace("removed serverString '{}' from serverDispatcherRelation. new refcount is {}", serverString, refCount);

				if (refCount == 0) {
					// .. and shutdown the dispatcher if there's no further reference
					log.debug("refCount reached 0. shutting down session and all related stuff.");
					ctsc.getDispatcher().shutdown();
					ctsc.getDispatcher().setReleased();

					CloseFuture closeFuture = ctsc.getSession().closeOnFlush();

					closeFuture.addListener(new IoFutureListener<IoFuture>() {

						@Override
						public void operationComplete(IoFuture future) {

							// shutdown threads/executors in filterchain once the session has been closed
							if (ctsc.getFilterchainWorkerPool() != null) {
								ctsc.getFilterchainWorkerPool().shutdown();
							}
							// dispose the MINA connector
							ctsc.getConnector().dispose();

							if (serverDispatcherRelation.isEmpty()) {
								log.debug("serverDispatcherRelation map is empty. Signalling complete network connection shutdown now.");
								monitorCompleteShutdown.signal();
							}
						}
					});
					result = true;
				} else {
					log.debug("refCount={}. put back the ClientToServerConnection.", refCount);
					serverDispatcherRelation.put(serverString, ctsc);
				}
			} else {
				log.debug("no ServerDispatcherRelation found for {}. Maybe remote object is already released?", serverString);
			}
		}

		return result;
	}
}
