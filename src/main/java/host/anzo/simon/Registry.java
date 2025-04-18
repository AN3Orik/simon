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

import host.anzo.simon.codec.base.SimonProtocolCodecFactory;
import host.anzo.simon.exceptions.LookupFailedException;
import host.anzo.simon.exceptions.NameBindingException;
import host.anzo.simon.ssl.SslContextFactory;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.logging.LoggingFilter;
import org.apache.mina.filter.ssl.SslFilter;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.util.concurrent.ExecutorService;

/**
 * The SIMON server acts as a registry for remote objects. So, Registry is
 * SIMON's internal server implementation
 *
 * @author achristian
 */
@Slf4j
public final class Registry {
	/**
	 * The address in which the registry is listening
	 */
	private InetAddress address;

	/**
	 * The port on which the registry is listening
	 */
	private int port;

	/**
	 * The Distaptcher for all incoming connections
	 */
	private Dispatcher dispatcher;

	/**
	 * the socket acceptor
	 */
	private IoAcceptor acceptor;

	/**
	 * The pool in which the dispatcher, acceptor and registry lives
	 */
	private ExecutorService threadPool;

	/**
	 * A thread pool for the filterchain => more performance
	 */
	private ExecutorService filterchainWorkerPool;

	/**
	 * Name of the protocol factory to use
	 */
	private String protocolFactoryClassName;
	private SslContextFactory sslContextFactory;

	// See: http://dev.root1.de/issues/127
	private ClassLoader classLoader = getClass().getClassLoader();

	/**
	 * started flag
	 *
	 * @since 1.2.0
	 */
	private boolean started;

	/**
	 * stopped flag
	 *
	 * @since 1.2.0
	 */
	private boolean stopped;

	/**
	 * Creates a registry
	 *
	 * @param address                  the interface address on which the socketserver listens on
	 * @param port                     the port on which the socketserver listens on
	 * @param threadPool               the thread pool implementation which is forwarded to the dispatcher
	 * @param protocolFactoryClassName the full classname of the class that describes to network protocol
	 * @throws IOException if there are problems with creating the mina socketserver
	 */
	protected Registry(InetAddress address, int port, ExecutorService threadPool, String protocolFactoryClassName) throws IOException {
		this(address, port, threadPool, protocolFactoryClassName, null);
	}

	/**
	 * Creates a SSL powered registry
	 *
	 * @param address                  the interface address on which the socketserver listens on
	 * @param port                     the port on which the socketserver listens on
	 * @param threadPool               the thread pool implementation which is forwarded to the dispatcher
	 * @param protocolFactoryClassName the full classname of the class that describes to network protocol
	 * @param sslContextFactory        the factory which is used to get the server ssl context
	 * @throws IOException if there are problems with creating the mina socketserver
	 */
	protected Registry(InetAddress address, int port, ExecutorService threadPool, String protocolFactoryClassName, SslContextFactory sslContextFactory) throws IOException {
		log.debug("begin");
		this.address = address;
		this.port = port;
		this.threadPool = threadPool;
		this.protocolFactoryClassName = protocolFactoryClassName;
		this.sslContextFactory = sslContextFactory;
		log.debug("end");
	}

	/**
	 * Starts the registry thread. After stopping, a registry cannot start again.
	 * One need to create a new registry.
	 * @param sessionListener session listener
	 * @throws IOException              if there's a problem getting a selector for the non-blocking
	 *                                  network communication, or if the
	 * @throws IllegalArgumentException if specified protocol codec cannot be used
	 * @throws IllegalStateException    if registry is already started or has been stopped.
	 */
	public void start(SimonSessionListener sessionListener) throws IOException {
		if (stopped) {
			throw new IllegalStateException("Stopped registry cannot start again.");
		}
		if (started) {
			throw new IllegalStateException("Registry already started.");
		}
		try {
			started = true;
			log.debug("begin");

			dispatcher = new Dispatcher(null, classLoader, threadPool);
			log.debug("dispatcher created");

			dispatcher.setSessionListener(sessionListener);

			acceptor = new NioSocketAcceptor();

			// currently this check is senseless. But in future we may provide more acceptor types?!
			if (acceptor instanceof NioSocketAcceptor) {
				NioSocketAcceptor nioSocketAcceptor = (NioSocketAcceptor) acceptor;

				log.debug("setting 'TcpNoDelay' on NioSocketAcceptor");
				nioSocketAcceptor.getSessionConfig().setTcpNoDelay(true);

				log.debug("setting 'ReuseAddress' on NioSocketAcceptor");
				nioSocketAcceptor.setReuseAddress(true);

				// FIXME workaround for http://dev.root1.de/issues/show/77
				try {
					ServerSocketChannel channel = ServerSocketChannel.open();
					channel.configureBlocking(false);
					ServerSocket socket = channel.socket();
					int receiveBufferSize = socket.getReceiveBufferSize();
					try {
						socket.close();
						channel.close();
					} catch (Exception ignored) {
						// close the temporary socket and channel and ignore all errors
					}
					log.debug("setting 'ReceiveBufferSize' on NioSocketAcceptor to {}", receiveBufferSize);
					nioSocketAcceptor.getSessionConfig().setReceiveBufferSize(receiveBufferSize);
				} catch (IOException ex) {
					log.debug("Not able to get readbuffersize from a default NIO socket. Error: {}", ex.getMessage());
					if (nioSocketAcceptor.getSessionConfig().getReadBufferSize() == 1024 &&
							System.getProperty("os.name").equals("Windows 7")) {
						log.warn("Server may have a drastic performance loss. Please consult 'http://dev.root1.de/issues/show/77' for more details.");
					}
				}
				// end of workaround
			}

			if (sslContextFactory != null) {
				SSLContext context = sslContextFactory.getSslContext();

				if (context != null) {
					SslFilter sslFilter = new SslFilter(context);
					acceptor.getFilterChain().addLast("sslFilter", sslFilter);
					log.debug("SSL ON");
				} else {
					log.warn("SSLContext retrieved from SslContextFactory was 'null', so starting WITHOUT SSL!");
				}
			}

			// only add the logging filter if trace is enabled
			if (log.isTraceEnabled()) {
				acceptor.getFilterChain().addLast("logger", new LoggingFilter());
			}

			// don't use a threading model on filter level
			//filterchainWorkerPool = new OrderedThreadPoolExecutor();
			//acceptor.getFilterChain().addLast("executor", new ExecutorFilter(filterchainWorkerPool));

			SimonProtocolCodecFactory protocolFactory = null;
			try {

				protocolFactory = Utils.getProtocolFactoryInstance(protocolFactoryClassName);
			} catch (ClassNotFoundException e) {
				log.error("ClassNotFoundException while preparing ProtocolFactory", e);
				throw new IllegalArgumentException(e);
			} catch (InstantiationException e) {
				log.error("InstantiationException while preparing ProtocolFactory", e);
				throw new IllegalArgumentException(e);
			} catch (IllegalAccessException e) {
				log.error("IllegalAccessException while preparing ProtocolFactory", e);
				throw new IllegalArgumentException(e);
			}

			protocolFactory.setup(true);
			acceptor.getFilterChain().addLast("codec", new ProtocolCodecFilter(protocolFactory));

			acceptor.setHandler(dispatcher);
			log.trace("Configuring acceptor with default values: write_timeout={}sec dgc_interval={}sec", Statics.DEFAULT_WRITE_TIMEOUT, Statics.DEFAULT_IDLE_TIME);
			setKeepAliveInterval(Statics.DEFAULT_IDLE_TIME);
			setKeepAliveTimeout(Statics.DEFAULT_WRITE_TIMEOUT);

			log.trace("Listening on {} on port {}", address, port);
			acceptor.bind(new InetSocketAddress(address, port));

			log.debug("acceptor thread created and started");
			log.debug("end");
		} catch (RuntimeException e) {
			started = false;
			throw e;
		} catch (IOException e) {
			started = false;
			throw e;
		}
	}

	/**
	 * Sets the keep alive timeout time in seconds for this registry.
	 *
	 * @param seconds time in seconds
	 */
	public void setKeepAliveTimeout(int seconds) {
		acceptor.getSessionConfig().setWriteTimeout(seconds);
		dispatcher.setPingTimeOut(seconds);
		log.debug("setting KeepAlive timeout to {} sec.", seconds);
	}

	/**
	 * Sets the keep alive interval time in seconds for this registry
	 *
	 * @param seconds time in seconds
	 */
	public void setKeepAliveInterval(int seconds) {
		acceptor.getSessionConfig().setIdleTime(IdleStatus.BOTH_IDLE, seconds);
		log.debug("setting KeepAlive interval to {} sec.", seconds);
	}

	/**
	 * Gets the keep alive timeout time in seconds of this registry.
	 *
	 * @return current set keep alive timeout
	 */
	public int getKeepAliveTimeout() {
		return acceptor.getSessionConfig().getWriteTimeout();
	}

	/**
	 * Gets the keep alive interval time in seconds of this registry.
	 *
	 * @return current set keep alive interval
	 */
	public int getKeepAliveInterval() {
		return acceptor.getSessionConfig().getIdleTime(IdleStatus.BOTH_IDLE);
	}

	/**
	 * Stops the registry. This clears the {@link LookupTable} in the dispatcher, stops the
	 * acceptor and the {@link Dispatcher}. After stopping this registry, no
	 * further connection/communication is possible with this registry.
	 */
	public void stop() {
		stopped = true;
		log.trace("begin");

		log.trace("Unbind Acceptor ...");
		acceptor.unbind();

		log.trace("Shutdown Dispatcher ...");
		dispatcher.shutdown();

		log.trace("Dispose Acceptor ...");
		acceptor.dispose();

		if (filterchainWorkerPool != null) {
			log.trace("Shutdown FilterchainWorkerPool ...");
			filterchainWorkerPool.shutdown();
		}

		log.trace("end");
	}

	/**
	 * Binds a remote object to the registry's own {@link LookupTable}
	 *
	 * @param name         a name for object to bind
	 * @param remoteObject the object to bind
	 * @throws NameBindingException  if there are problems binding the remoteobject to the
	 *                               registry
	 * @throws IllegalStateException if registry is not yet started or already stopped
	 */
	public void bind(String name, Object remoteObject) throws NameBindingException {

		if (!started) {
			throw new IllegalStateException("Registry not yet started.");
		}
		if (stopped) {
			throw new IllegalStateException("Registry already stopped.");
		}

		if (!Utils.isValidRemote(remoteObject)) {
			throw new IllegalArgumentException("Provided remote object is not marked with SimonRemote or Remote annotation!");
		}

		try {
			if (dispatcher.getLookupTable().getRemoteObjectContainer(name) != null) {
				throw new NameBindingException("a remote object with the name '" + name +
						"' is already bound to this registry. unbind() first, or alternatively rebind().");
			}
		} catch (LookupFailedException e) {
			// nothing to do
		}
		dispatcher.getLookupTable().putRemoteBinding(name, remoteObject);
	}

	/**
	 * Binds the object to the {@link Registry} and publishes it to the network,
	 * so that they can be found with {@link Simon#searchRemoteObjects(int)} or
	 * {@link Simon#searchRemoteObjects(SearchProgressListener, int)}
	 *
	 * @param name         a name for the object to bind and publish
	 * @param remoteObject the object to bind and publish
	 * @throws NameBindingException if binding fails
	 */
	public void bindAndPublish(String name, Object remoteObject) throws NameBindingException {
		bind(name, remoteObject);
		try {
			Simon.publish(new SimonPublication(address, port, name));
		} catch (IOException e) {
			unbind(name);
			throw new NameBindingException("can't publish '" + name + "'. object is not bind! error=" + e.getMessage());
		}
	}


	public void bindAndPublishRemote(String name, Object remoteObject, InetSocketAddress remoteRegistry) throws NameBindingException {
		bind(name, remoteObject);
		try {
			Simon.publishRemote(new SimonPublication(address, port, name), remoteRegistry);
		} catch (IOException e) {
			unbind(name);
			throw new NameBindingException("can't publish '" + name + "'. object is not bind! error=" + e.getMessage());
		}
	}

	/**
	 * Unbinds a remote object from the registry's own {@link LookupTable}. If
	 * it's published, it's removed from the list of published objects
	 *
	 * @param name the object to unbind (and unpublish, if published)
	 * @return true, if unpublish succeeded, false, if object wasn't published
	 * and though can't be unpublished
	 */
	public boolean unbind(String name) {
		//TODO what to do with already connected users?
		dispatcher.getLookupTable().releaseRemoteBinding(name);
		return Simon.unpublish(new SimonPublication(address, port, name));
	}

	/**
	 * Unpublish a already published remote object.
	 *
	 * @param name the object to unpublish, if published
	 * @return true, if unpublish succeeded, false, if object wasn't published
	 * and though can't be unpublished
	 */
	public boolean unpublish(String name) {
		return Simon.unpublish(new SimonPublication(address, port, name));
	}

	/**
	 * As the name says, it re-binds a remote object. This method shows the same
	 * behavior as the following two commands in sequence:<br>
	 * <br>
	 * <code>
	 * unbind(name);<br>
	 * bind(name, remoteObject);
	 * </code>
	 *
	 * @param name         the name of the object to rebind
	 * @param remoteObject the object to rebind
	 */
	public void rebind(String name, Object remoteObject) {
		unbind(name);
		try {
			bind(name, remoteObject);
		} catch (NameBindingException e) {
			// this should never happen, nevertheless, we log it
			log.warn("rebind() should never throw an NameBindingException. Contact SIMON author and send him this log.");
		}
	}

	/**
	 * Returns whether the registry is running and active or not
	 *
	 * @return boolean
	 */
	public boolean isRunning() {
		return (dispatcher != null && acceptor != null &&
				(dispatcher.isRunning() || acceptor.isActive() ||
						(filterchainWorkerPool != null && !filterchainWorkerPool.isTerminated())));
	}

	/**
	 * Returns a object that lets you get some network related information on
	 * the session of the given remote object (an instance of {@link SimonProxy}
	 *
	 * @return an implementation of {@link SimonRegistryStatistics} that gives
	 * access to the statistics data of this {@link Registry}
	 */
	public SimonRegistryStatistics getStatistics() {
		return new RegistryStatistics(acceptor.getStatistics());
	}

	/**
	 * Returns the {@link Dispatcher} associated with this registry.
	 *
	 * @return the related dispatcher
	 */
	public Dispatcher getDispatcher() {
		return dispatcher;
	}

	/**
	 * The classloader which is used to load remote interface classes (used in remote callbacks f.i.).
	 *
	 * @return ClassLoader
	 * @since 1.2.0
	 */
	public ClassLoader getClassLoader() {
		return classLoader;
	}

	/**
	 * Set the classloader which is used to load remote interface classes (used in remote callbacks f.i.)
	 *
	 * @param classLoader
	 * @since 1.2.0
	 */
	public void setClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}
}
