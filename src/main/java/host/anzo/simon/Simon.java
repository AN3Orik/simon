/*
 * Copyright © 2016 BDO-Emu authors. All rights reserved.
 * Viewing, editing, running and distribution of this software strongly prohibited.
 * Author: xTz, Anton Lasevich, Tibald
 */

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
import host.anzo.simon.exceptions.*;
import host.anzo.simon.io.AcceptAllBufferAllocator;
import host.anzo.simon.ssl.SslContextFactory;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IdleStatus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * This is SIMONs core class which contains all the core functionality like
 * setting up a SIMON server or lookup a remote object from the client side
 */
@Slf4j
public class Simon {
	/**
	 * Official port assigned by IANA
	 *
	 * @see <a href="http://www.iana.org/assignments/service-names-port-numbers">IANA Port Assignments</a>
	 * @since 1.2.0
	 */
	public final static int DEFAULT_PORT = 4753;

	/**
	 * Map holding custom invoke timeouts for specific remote methods (including callbacks).
	 */
	private static final Map<Method, Integer> customInvokeTimeoutMap = new HashMap<>();

	static {
		IoBuffer.setAllocator(new AcceptAllBufferAllocator());
		String property = System.getProperty("host.anzo.simon.debug", "false");
		boolean debugEnabled = Boolean.parseBoolean(property);

		if (debugEnabled) {
			System.out.println("ENABLING SIMON DEBUG LOG");
			try {
				File f = new File("host.anzo.simon.debuglogging.properties");
				if (!f.exists()) {
					System.out.println(
							"SIMON debug logging properties does not exist. Creating default '" + f.getAbsolutePath() +
									"' ...");
					FileWriter fw = new FileWriter(f);
					fw.write("handlers= java.util.logging.FileHandler, java.util.logging.ConsoleHandler" + "\n");
					fw.write(".level= ALL" + "\n");
					fw.write("java.util.logging.FileHandler.pattern = host.anzo.simon_debug.log" + "\n");
					fw.write("java.util.logging.FileHandler.limit = 500000" + "\n");
					fw.write("java.util.logging.FileHandler.count = 1" + "\n");
					fw.write("java.util.logging.FileHandler.formatter = host.anzo.simon.utils.ConsoleLogFormatter" +
							"\n");
					fw.write("java.util.logging.ConsoleHandler.level = ALL" + "\n");
					fw.write("java.util.logging.ConsoleHandler.formatter = host.anzo.simon.utils.ConsoleLogFormatter" +
							"\n");
					fw.write("host.anzo.simon.level = ALL" + "\n");
					fw.write("org.apache.mina.filter.logging.LoggingFilter = INFO" + "\n");
					fw.close();
				} else {
					System.out.println("Using existing debug logging properties: " + f.getAbsolutePath());
				}
				FileInputStream is = new FileInputStream(f);
				LogManager.getLogManager().readConfiguration(is);
			} catch (IOException ex) {
				java.util.logging.Logger.getLogger(Simon.class.getName()).log(Level.SEVERE, null, ex);
			}

			System.out.println("ENABLING SIMON DEBUG LOG *DONE*");
		}
	}

	/**
	 * The size of the used thread pool. -1 indicates a cached thread pool.
	 */
	private static int poolSize = -1;
	/**
	 * A list of publishments. This is used by the publish service server.
	 */
	private static final List<SimonPublication> publishments = new ArrayList<SimonPublication>();
	/**
	 * the publish service server that is used when remote objects are published
	 */
	private static PublishService publishService;
	/**
	 * TODO document me
	 */
	private static PublicationSearcher publicationSearcher;
	/**
	 * Identifies the class, that is used as SIMON's standard protocol codec
	 * factory
	 */
	protected static final String SIMON_STD_PROTOCOL_CODEC_FACTORY = SimonProtocolCodecFactory.class.getName();
	/**
	 * The current set name of the protocol factory class
	 */
	private static String protocolFactoryClassName = SIMON_STD_PROTOCOL_CODEC_FACTORY;
	/**
	 * A list with all active/still alive LookupTables ever created
	 */
	private static final List<LookupTable> lookupTableList = new ArrayList<LookupTable>();

	/**
	 * Creates a registry listening on all interfaces with the last known worker
	 * thread pool size set by {@link Simon#setWorkerThreadPoolSize}
	 *
	 * @param port the port on which SIMON listens for connections
	 * @return the created registry object
	 * @throws UnknownHostException if no IP address for the host could be found
	 * @throws IOException          if there is a problem with the networking layer
	 */
	public static Registry createRegistry(int port) throws UnknownHostException, IOException {
		return createRegistry(InetAddress.getByName("0.0.0.0"), port);
	}

	/**
	 * Creates a registry listening on all interfaces with the last known worker
	 * thread pool size set by {@link Simon#setWorkerThreadPoolSize} and the
	 * SIMON's default port {@link Simon#DEFAULT_PORT}.
	 *
	 * @return the created registry object
	 * @throws UnknownHostException if no IP address for the host could be found
	 * @throws IOException          if there is a problem with the networking layer
	 * @since 1.2.0
	 */
	public static Registry createRegistry() throws UnknownHostException, IOException {
		return createRegistry(InetAddress.getByName("0.0.0.0"), DEFAULT_PORT);
	}

	/**
	 * Creates a registry listening on a specific network interface, identified
	 * by the given {@link InetAddress} with the last known worker thread pool
	 * size set by {@link Simon#setWorkerThreadPoolSize}.
	 *
	 * @param address the {@link InetAddress} the registry is bind to
	 * @param port    the port the registry is bind to
	 * @return the created registry
	 * @throws IOException              if there is a problem with the networking layer
	 * @throws IllegalArgumentException i.e. if specified protocol codec factory
	 *                                  class cannot be used
	 */
	public static Registry createRegistry(InetAddress address, int port) throws IOException, IllegalArgumentException {
		log.debug("begin");
		Registry registry = new Registry(address, port, getThreadPool(), protocolFactoryClassName);
		log.debug("end");
		return registry;
	}

	/**
	 * Creates a registry listening on a specific network interface, identified
	 * by the given {@link InetAddress} with the last known worker thread pool
	 * size set by {@link Simon#setWorkerThreadPoolSize} and the SIMON's default
	 * port {@link Simon#DEFAULT_PORT}
	 *
	 * @param address the {@link InetAddress} the registry is bind to
	 * @return the created registry
	 * @throws IOException              if there is a problem with the networking layer
	 * @throws IllegalArgumentException i.e. if specified protocol codec factory
	 *                                  class cannot be used
	 * @since 1.2.0
	 */
	public static Registry createRegistry(InetAddress address) throws IOException, IllegalArgumentException {
		log.debug("begin");
		Registry registry = new Registry(address, DEFAULT_PORT, getThreadPool(), protocolFactoryClassName);
		log.debug("end");
		return registry;
	}

	/**
	 * Creates a registry listening on a specific network interface, identified
	 * by the given {@link InetAddress} with the last known worker thread pool
	 * size set by {@link Simon#setWorkerThreadPoolSize}. The communication is
	 * done via SSL encryption provided by the given SslContextFactory
	 *
	 * @param sslContextFactory the factory that provides the ssl context for
	 *                          the SSL powered registry
	 * @param address           the {@link InetAddress} the registry is bind to
	 * @param port              the port the registry is bind to
	 * @return the created registry
	 * @throws IOException              if there is a problem with the networking layer
	 * @throws IllegalArgumentException i.e. if specified protocol codec factory
	 *                                  class cannot be used
	 */
	public static Registry createRegistry(SslContextFactory sslContextFactory, InetAddress address, int port) throws IOException, IllegalArgumentException {
		log.debug("begin");
		Registry registry = new Registry(address, port, getThreadPool(), protocolFactoryClassName, sslContextFactory);
		log.debug("end");
		return registry;
	}

	/**
	 * Creates a registry listening on a specific network interface, identified
	 * by the given {@link InetAddress} with the last known worker thread pool
	 * size set by {@link Simon#setWorkerThreadPoolSize} and the SIMON's default
	 * port {@link Simon#DEFAULT_PORT}. The communication is done via SSL
	 * encryption provided by the given SslContextFactory
	 *
	 * @param sslContextFactory the factory that provides the ssl context for
	 *                          the SSL powered registry
	 * @param address           the {@link InetAddress} the registry is bind to
	 * @return the created registry
	 * @throws IOException              if there is a problem with the networking layer
	 * @throws IllegalArgumentException i.e. if specified protocol codec factory
	 *                                  class cannot be used
	 * @since 1.2.0
	 */
	public static Registry createRegistry(SslContextFactory sslContextFactory, InetAddress address) throws IOException, IllegalArgumentException {
		log.debug("begin");
		Registry registry = new Registry(address, DEFAULT_PORT, getThreadPool(), protocolFactoryClassName, sslContextFactory);
		log.debug("end");
		return registry;
	}

	/**
	 * Creates a interface lookup object that is used to lookup remote objects.
	 * <br> Lookup is made via a known interface of the remote object.
	 *
	 * @param host the name of the host on which the registry server runs
	 * @param port the port on which the registry server is listening
	 * @return the lookup object
	 * @throws UnknownHostException if the specified hostname is unknown
	 * @since version 1.1.0
	 */
	public static Lookup createInterfaceLookup(String host, int port) throws UnknownHostException {
		return new InterfaceLookup(host, port);
	}

	/**
	 * Creates a interface lookup object that is used to lookup remote objects.
	 * <br> Lookup is made via a known interface of the remote object.
	 *
	 * @param address the address of the host on which the registry server runs
	 * @param port    the port on which the registry server is listening
	 * @return the lookup object
	 * @since version 1.1.0
	 */
	public static InterfaceLookup createInterfaceLookup(InetAddress address, int port) {
		return new InterfaceLookup(address, port);
	}

	/**
	 * Creates a interface lookup object that is used to lookup remote objects.
	 * The connection is done via SIMON's default port {@link Simon#DEFAULT_PORT}.
	 * <br> Lookup is made via a known interface of the remote object.
	 *
	 * @param host the name of the host on which the registry server runs
	 * @return the lookup object
	 * @throws UnknownHostException if the specified hostname is unknown
	 * @since 1.2.0
	 */
	public static InterfaceLookup createInterfaceLookup(String host) throws UnknownHostException {
		return new InterfaceLookup(host, DEFAULT_PORT);
	}

	/**
	 * Creates a interface lookup object that is used to lookup remote objects.
	 * The connection is done via SIMON's default port {@link Simon#DEFAULT_PORT}.
	 * <br> Lookup is made via a known interface of the remote object.
	 *
	 * @param address the address of the host on which the registry server runs
	 * @return the lookup object
	 * @since 1.2.0
	 */
	public static Lookup createInterfaceLookup(InetAddress address) {
		return new InterfaceLookup(address, DEFAULT_PORT);
	}

	/**
	 * Creates a name lookup object that is used to lookup remote objects. <br>
	 * Lookup is made via a known name of the remote object.
	 *
	 * @param host the name of the host on which the registry server runs
	 * @param port the port on which the registry server is listening
	 * @return the lookup object
	 * @throws UnknownHostException if the specified hostname is unknown
	 * @since version 1.1.0
	 */
	public static Lookup createNameLookup(String host, int port) throws UnknownHostException {
		return new NameLookup(host, port);
	}

	/**
	 * Creates a name lookup object that is used to lookup remote objects. <br>
	 * Lookup is made via a known name of the remote object.
	 *
	 * @param address the address of the host on which the registry server runs
	 * @param port    the port on which the registry server is listening
	 * @return the lookup object
	 * @since version 1.1.0
	 */
	public static Lookup createNameLookup(InetAddress address, int port) {
		return new NameLookup(address, port);
	}

	/**
	 * Creates a name lookup object that is used to lookup remote objects. The
	 * connection is done via SIMON's default port {@link Simon#DEFAULT_PORT}.<br>
	 * Lookup is made via a known name of the remote object.
	 *
	 * @param host the name of the host on which the registry server runs
	 * @return the lookup object
	 * @throws UnknownHostException if the specified hostname is unknown
	 * @since 1.2.0
	 */
	public static Lookup createNameLookup(String host) throws UnknownHostException {
		return new NameLookup(host, DEFAULT_PORT);
	}

	/**
	 * Creates a name lookup object that is used to lookup remote objects. The
	 * connection is done via SIMON's default port {@link Simon#DEFAULT_PORT}.<br>
	 * Lookup is made via a known name of the remote object.
	 *
	 * @param address the address of the host on which the registry server runs
	 * @return the lookup object
	 * @since 1.2.0
	 */
	public static Lookup createNameLookup(InetAddress address) {
		return new NameLookup(address, DEFAULT_PORT);
	}

	/**
	 * Gets the InetSocketAddress used on the remote-side of the given proxy
	 * object
	 *
	 * @param proxyObject the proxy object
	 * @return the InetSocketAddress on the remote-side
	 */
	public static InetSocketAddress getRemoteInetSocketAddress(Object proxyObject) {
		return (InetSocketAddress) getSimonProxy(proxyObject).getRemoteSocketAddress();
	}

	/**
	 * Gets the InetSocketAddress used on the local-side of the given proxy
	 * object
	 *
	 * @param proxyObject the proxy object
	 * @return the InetSocketAddress on the local-side
	 */
	public static InetSocketAddress getLocalInetSocketAddress(Object proxyObject) {
		return (InetSocketAddress) getSimonProxy(proxyObject).getLocalSocketAddress();
	}

	/**
	 * Retrieves {@link SimonProxy} invocation handler wrapped in a simple proxy
	 *
	 * @param o the object that holds the proxy
	 * @return the extracted SimonProxy
	 * @throws IllegalArgumentException if the object does not contain a
	 *                                  SimonProxy invocation handler
	 */
	protected static SimonProxy getSimonProxy(Object o) throws IllegalArgumentException {
		if (o instanceof Proxy) {
			InvocationHandler invocationHandler = Proxy.getInvocationHandler(o);
			log.trace("Got invocation handler ...");
			if (invocationHandler instanceof SimonProxy) {
				log.trace("Yeeha. It's a SimonProxy ...");
				return (SimonProxy) invocationHandler;
			} else {
				throw new IllegalArgumentException(
						"the proxys invocationhandler is not an instance of SimonProxy. Given object was: " + o);
			}
		} else {
			throw new IllegalArgumentException(
					"the argument is not a releaseable remote object. Given object was: " + o);
		}
	}

	/**
	 * @param o the object that holds the proxy
	 * @return proxy connection session ID
	 */
	public static long getSessionId(Object o) {
		final SimonProxy simonProxy = getSimonProxy(o);
		return simonProxy.getSessionId();
	}

	/**
	 * Returns the reference to the worker thread pool
	 *
	 * @return the threadPool
	 */
	protected static ExecutorService getThreadPool() {
		if (poolSize == -1) {
			return Executors.newCachedThreadPool(new NamedThreadPoolFactory(Statics.DISPATCHER_WORKERPOOL_NAME));
		} else if (poolSize == 1) {
			return Executors.newSingleThreadExecutor(new NamedThreadPoolFactory(Statics.DISPATCHER_WORKERPOOL_NAME));
		} else {
			return Executors.newFixedThreadPool(poolSize, new NamedThreadPoolFactory(Statics.DISPATCHER_WORKERPOOL_NAME));
		}
	}

	/**
	 * Sets the size of the worker thread pool.<br> This will setting only
	 * affect new pool that have to be created in future. If given size has
	 * value -1, a new pool will create new threads as needed, but will reuse
	 * previously constructed threads when they are available. This is the most
	 * common setting. Old, for 60 seconds unused threads will be removed. These
	 * pools will typically improve the performance of programs that execute
	 * many short-lived asynchronous tasks. See documentation of {@link Executors#newCachedThreadPool()}<br>
	 * <p>
	 * If size has value >=1, a new pool has a fixed size by the given value
	 *
	 * @param size the size of the used worker thread pool
	 */
	public static void setWorkerThreadPoolSize(int size) {
		poolSize = size;
	}

	/**
	 * Sets the DGC's interval time in milliseconds
	 *
	 * @param milliseconds time in milliseconds
	 * @deprecated use {@link Simon#setDefaultKeepAliveInterval(int)} instead!
	 */
	@Deprecated
	public static void setDgcInterval(int milliseconds) {
		Statics.DEFAULT_IDLE_TIME = milliseconds / 1000;
	}

	/**
	 * Gets the DGC's interval time in milliseconds
	 *
	 * @return the current set DGC interval
	 * @deprecated use {@link Simon#getKeepAliveInterval()} instead!
	 */
	@Deprecated
	public static int getDgcInterval() {
		return Statics.DEFAULT_IDLE_TIME * 1000;
	}

	/**
	 * Sets the default connect timeout for establishing a connection via Lookup
	 *
	 * @param millis time in milliseconds to wait for the established connection
	 */
	public static void setDefaultConnectTimeout(int millis) {
		log.debug("setting default connect timeout to {} ms.", millis);
		Statics.DEFAULT_CONNECT_TIMEOUT = millis;
	}

	/**
	 * Gets the default connect timeout in milliseconds. This value is the
	 * used default value for all new connections.
	 *
	 * @return time in milliseconds to wait for the established connection
	 */
	public static int getDefaultConnectTimeout() {
		return Statics.DEFAULT_CONNECT_TIMEOUT;
	}

	/**
	 * Sets the keep alive default interval time in seconds. This value is used
	 * as a default value for all new connections.
	 *
	 * @param seconds time in seconds
	 */
	public static void setDefaultKeepAliveInterval(int seconds) {
		log.debug("setting default keep alive interval to {} sec.", seconds);
		Statics.DEFAULT_IDLE_TIME = seconds;
	}


	/**
	 * Gets the default keep-alive interval time in seconds. This value is the
	 * used default value for all new connections.
	 *
	 * @return the current set keep alive interval
	 */
	public static int getKeepAliveInterval() {
		return Statics.DEFAULT_IDLE_TIME;
	}

	/**
	 * Sets the default keep alive timeout time in seconds. This value is used
	 * as a default value for all new connections.
	 *
	 * @param seconds time in seconds
	 */
	public static void setDefaultKeepAliveTimeout(int seconds) {
		log.debug("setting default keep alive timeout to {} sec.", seconds);
		Statics.DEFAULT_WRITE_TIMEOUT = seconds;
	}

	/**
	 * Gets the default network write timeout time in seconds. This value is the
	 * used default value for all new connections.
	 *
	 * @return the current set network write timeout
	 */
	public static int getDefaultKeepAliveTimeout() {
		return Statics.DEFAULT_WRITE_TIMEOUT;
	}

	/**
	 * Sets the keep alive interval time in seconds for the specified remote
	 * object
	 *
	 * @param remoteObject
	 * @param seconds      time in seconds
	 * @throws IllegalArgumentException if the object is not a valid remote
	 *                                  object
	 */
	public static void setKeepAliveInterval(Object remoteObject, int seconds) {
		log.debug("setting keep alive interval on {} to {} sec.", remoteObject, seconds);
		getSimonProxy(remoteObject).getIoSession().getConfig().setIdleTime(IdleStatus.BOTH_IDLE, seconds);
	}

	/**
	 * Gets the keep alive interval time in seconds of the given remote object.
	 *
	 * @param remoteObject
	 * @return current set keep alive interval of given remote object
	 * @throws IllegalArgumentException if the object is not a valid remote
	 *                                  object
	 */
	public static int getKeepAliveInterval(Object remoteObject) {
		return getSimonProxy(remoteObject).getIoSession().getConfig().getIdleTime(IdleStatus.BOTH_IDLE);
	}

	/**
	 * Sets the keep alive timeout time in seconds for the specified remote
	 * object.
	 *
	 * @param remoteObject
	 * @param seconds      time in seconds
	 * @throws IllegalArgumentException if the object is not a valid remote
	 *                                  object
	 */
	public static void setKeepAliveTimeout(Object remoteObject, int seconds) {
		log.debug("setting keep alive timeout on {} to {} sec.", remoteObject, seconds);
		getSimonProxy(remoteObject).getIoSession().getConfig().setWriteTimeout(seconds);
	}

	/**
	 * Gets the keep alive timeout time in seconds of the given remote object.
	 *
	 * @param remoteObject
	 * @return current set keep alive timeout of given remote object
	 * @throws IllegalArgumentException if the object is not a valid remote
	 *                                  object
	 */
	public static int getKeepAliveTimeout(Object remoteObject) throws IllegalArgumentException {
		return getSimonProxy(remoteObject).getIoSession().getConfig().getWriteTimeout();
	}

	/**
	 * Publishes a remote object. If not already done, publish service thread is
	 * started.
	 *
	 * @param simonPublication the object to publish
	 * @throws IOException if the publish service cannot be started due to IO
	 *                     problems
	 */
	protected static void publish(SimonPublication simonPublication) throws IOException {
		if (publishments.isEmpty()) {
			publishService = new PublishService(publishments);
			publishService.start();
		}
		publishments.add(simonPublication);
	}

	protected static void publishRemote(SimonPublication simonPublication, InetSocketAddress remoteRegistry) throws IOException {
		InterfaceLookup remotePublishLookup = Simon.createInterfaceLookup(remoteRegistry.getAddress(), remoteRegistry.getPort());
		try {
			SimonRemotePublish simonRemotePublish = (SimonRemotePublish) remotePublishLookup.lookup(SimonRemotePublish.class.getCanonicalName());
			simonRemotePublish.publish(simonPublication);
			remotePublishLookup.release(simonRemotePublish);
		} catch (LookupFailedException ex) {

		} catch (EstablishConnectionFailed ex) {

		}
	}

	/**
	 * Unpublishs a already published {@link SimonPublication}. If there are no
	 * more publications available, shutdown the publish service.
	 *
	 * @param simonPublication the publication to unpublish
	 * @return true, if elemet was present and is now removed, false if not
	 */
	protected static boolean unpublish(SimonPublication simonPublication) {
		boolean result = publishments.remove(simonPublication);
		if (publishments.isEmpty() && publishService != null && publishService.isAlive()) {
			publishService.shutdown();
		}
		return result;
	}

	/**
	 * Creates a background thread that searches for published remote objects on
	 * the local network
	 *
	 * @param listener   a {@link SearchProgressListener} implementation which is
	 *                   informed about the current search progress
	 * @param searchTime the time the background search thread spends for
	 *                   searching published remote objects
	 * @return a {@link PublicationSearcher} which is used to access the search
	 * result
	 */
	public static PublicationSearcher searchRemoteObjects(SearchProgressListener listener, int searchTime) {
		if (publicationSearcher == null || !publicationSearcher.isSearching()) {
			try {
				publicationSearcher = new PublicationSearcher(listener, searchTime);
				publicationSearcher.start();
			} catch (IOException e) {
				// TODO what to do?
				e.printStackTrace();
			}
		} else {
			throw new IllegalStateException("another search is currently in progress ...");
		}
		return publicationSearcher;
	}

	/**
	 * Starts a search on the local network for published remote objects. <br>
	 * <b><u>Be warned:</u> This method blocks until the search is finished or
	 * the current thread is interrupted</b>
	 *
	 * @param searchTime the time that is spend to search for published remote
	 *                   objects
	 * @return a {@link List} of {@link SimonPublication}s
	 */
	public static List<SimonPublication> searchRemoteObjects(int searchTime) {
		if (publicationSearcher == null || !publicationSearcher.isSearching()) {

			try {
				publicationSearcher = new PublicationSearcher(null, searchTime);
				publicationSearcher.run(); // call run without starting the thread. call is synchronously!
			} catch (IOException e) {
				// TODO what to do?
				e.printStackTrace();
				return null;
			}

			return publicationSearcher.getNewPublications();
		} else {
			throw new IllegalStateException("another search is currently in progress ...");
		}
	}

	/**
	 * Sets class name for the protocol codec factory to use for all future
	 * <code>createRegistry()</code> or
	 * <code>lookup()</code> calls. <i>This does not affect already created
	 * registry or already established sessions.</i>
	 *
	 * @param protocolFactoryClassName a class name like
	 *                                 "com.mydomain.myproject.codec.mySimonProtocolCodecFactory" which points
	 *                                 to a class, that extends
	 *                                 {@link SimonProtocolCodecFactory}. <i>The important thing is, that this
	 *                                 class correctly overrides
	 *                                 {@link SimonProtocolCodecFactory#setup(boolean)}. For further details,
	 *                                 look at {@link SimonProtocolCodecFactory}!</i>
	 * @throws IllegalAccessException if the class or its nullary constructor is
	 *                                not accessible.
	 * @throws InstantiationException if this Class represents an abstract
	 *                                class, an interface, an array class, a primitive type, or void; or if the
	 *                                class has no nullary constructor; or if the instantiation fails for some
	 *                                other reason.
	 * @throws ClassNotFoundException if the class is not found by the
	 *                                classloader. if so, please check your classpath.
	 * @throws ClassCastException     if the given class is no instance of
	 *                                {@link SimonProtocolCodecFactory}
	 */
	public static void setProtocolCodecFactory(String protocolFactoryClassName) throws InstantiationException, IllegalAccessException, ClassNotFoundException, ClassCastException {
		// testwise try to get the factory. if the specified class' name is not useable,
		// exceptions will be thrown and forwarded
		Utils.getProtocolFactoryInstance(protocolFactoryClassName);
		// if the above worked, save the class' name
		Simon.protocolFactoryClassName = protocolFactoryClassName;
	}

	/**
	 * Returns the current set class name for the protocol codec factory
	 *
	 * @return the name of the protocol codec class
	 */
	public static String getProtocolCodecFactory() {
		return Simon.protocolFactoryClassName;
	}

	/**
	 * Returns a object that lets you get some network related information on
	 * the session of the given remote object (an instance of {@link SimonProxy}
	 *
	 * @param remoteObject the remote object that is asked for the statistics
	 * @return an implementation of {@link SimonRemoteStatistics} that gives
	 * access to the statistics data
	 */
	public static SimonRemoteStatistics getStatistics(Object remoteObject) {
		SimonProxy simonProxy = getSimonProxy(remoteObject);
		return new RemoteStatistics(simonProxy.getIoSession());
	}

	/**
	 * Opens a raw channel to transfer data from the current station to the
	 * remote station described by the given
	 * <code>simonRemote</code>
	 *
	 * @param channelToken a token that identifies the already prepared raw
	 *                     channel from the remote station. Those token can only be created on the
	 *                     remote station. Thus a remote call which does the
	 *                     {@link Simon#prepareRawChannel(RawChannelDataListener, Object)} is needed
	 *                     in advance.
	 * @param simonRemote  the remote object which lives on the remote station
	 *                     which has a prepared raw data channel, related to the
	 *                     <code>channelToken</code>. Note: This <b>has to be</b> a remote object
	 *                     stub.
	 * @return the opened raw channel object
	 * @throws SimonRemoteException
	 */
	public static RawChannel openRawChannel(int channelToken, Object simonRemote) throws SimonRemoteException {
		log.debug("begin. token={}", channelToken);
		SimonProxy simonProxy = getSimonProxy(simonRemote);
		log.trace("simon proxy detail string for given simonRemote: {}", simonProxy.getDetailString());
		Dispatcher dispatcher = simonProxy.getDispatcher();
		log.trace("dispatcher for given simonRemote: {}", dispatcher);
		RawChannel rawChannel = dispatcher.openRawChannel(simonProxy.getIoSession(), channelToken);
		log.debug("raw channel for token {} is {}", channelToken, rawChannel);
		log.debug("end.");
		return rawChannel;
	}

	/**
	 * Prepare
	 * <code>simonRemote</code>'s internal message dispatcher for receiving raw
	 * data.<br/> The result of this method is a token, which identifies the
	 * channel on both sides: <ul> <li>on the <i>receiving side</i> with the
	 * registered
	 * {@link RawChannelDataListener},</li> <li>and on the <i>sending side</i>
	 * for opening the {@link RawChannel} by calling {@link Simon#openRawChannel(int, Object)}.</li>
	 * </ul> <br> This method has to be called on the receiving side.
	 *
	 * @param listener    the listener which gets all the received data related to
	 *                    this channel
	 * @param simonRemote a reference to the remote object whos {@link Dispatcher}
	 *                    is prepared to receive raw data.
	 * @return a token that identifies the prepared channel
	 * @throws SimonException
	 */
	public static int prepareRawChannel(RawChannelDataListener listener, Object simonRemote) throws SimonException {
		log.debug("preparing raw channel for listener {}", listener);
		Dispatcher dispatcher = getDispatcher(simonRemote);
		if (dispatcher != null) {
			return dispatcher.prepareRawChannel(listener);
		} else {
			throw new IllegalArgumentException(
					"Given SimonRemote is not found in any lookuptable: " + simonRemote.getClass());
		}
	}

	/**
	 * TODO document me
	 *
	 * @param lookupTable
	 */
	protected synchronized static void registerLookupTable(LookupTable lookupTable) {
		lookupTableList.add(lookupTable);
		log.trace("added {} to list of lookuptables. current size: {}", lookupTable, lookupTableList.size());
	}

	/**
	 * TODO document me
	 *
	 * @param lookupTable
	 */
	protected synchronized static void unregisterLookupTable(LookupTable lookupTable) {
		lookupTableList.remove(lookupTable);
		log.trace("removed {} from list of lookuptables. current size: {}", lookupTable, lookupTableList.size());
	}

	/**
	 * Searches the {@link LookupTable} for the given remote object and
	 * returns {@link Dispatcher} which is attached to this {@link LookupTable}.
	 *
	 * @param remoteObject
	 * @return the related {@link Dispatcher} or null, if no related dispatcher found
	 */
	private static Dispatcher getDispatcher(Object remoteObject) {
		for (LookupTable lookupTable : lookupTableList) {
			log.debug("searching in LookupTable {} for remote object {}", lookupTable, remoteObject);
			if (lookupTable.isSimonRemoteRegistered(remoteObject)) {
				return lookupTable.getDispatcher();
			}
		}
		return null;
	}

	/**
	 * Marks the object with SimonRemote to make it able to receive incoming
	 * calls.
	 *
	 * @param o the object to mark as an SimonRemote
	 * @return a marked (proxy) class
	 * @throws IllegalRemoteObjectException thrown in case of missing interfaces
	 *                                      of given object
	 * @since 1.1.0
	 */
	public static Object markAsRemote(Object o) {
		Class<?>[] interfaces = o.getClass().getInterfaces();
		if (interfaces.length == 0) {
			throw new IllegalRemoteObjectException("There need to be at least one interface to mark the given object as simon remote");
		}
		SimonRemoteMarker srm = new SimonRemoteMarker(o);
		Object newProxyInstance = Proxy.newProxyInstance(Simon.class.getClassLoader(), interfaces, srm);
		return newProxyInstance;
	}

	/**
	 * Tests if both objects denote the same remote object. Comparison is done
	 * on <ul> <li>remote object name</li> <li>underlying IO session</li> </ul>
	 * <p>
	 * If both objects denote a remote object and the values match for both
	 * objects, result will be true. In any other case, false is returned
	 *
	 * @param a
	 * @param b
	 * @return boolean
	 * @since 1.2.0
	 */
	public static boolean denoteSameRemoteObjekt(Object a, Object b) {

		if (Utils.isSimonProxy(a)) {

			if (Utils.isSimonProxy(b)) {

				SimonProxy proxyA = Simon.getSimonProxy(a);
				SimonProxy proxyB = Simon.getSimonProxy(b);

				if (proxyA.getRemoteObjectName().equals(proxyB.getRemoteObjectName()) &&
						proxyA.getIoSession().equals(proxyB.getIoSession())) {
					return true;
				}
			} else {
				log.debug("Object 'b' is not a SimonProxy instance");
			}
		} else {
			log.debug("Object 'a' is not a SimonProxy instance");
		}

		return false;
	}

	/**
	 * Returns the SIMON internal session id of a current running remote call.
	 * This method only works if called within a remote call. Means: Don't use it
	 * anywhere else than in the remote implementation. Be careful!
	 *
	 * @return SIMON internal session id
	 * @throws IllegalStateException if called from outside a remote call
	 */
	public static long getSessionId() {
		Thread currentThread = Thread.currentThread();
		if (currentThread instanceof ProcessMessageThread) {
			ProcessMessageThread thread = (ProcessMessageThread) currentThread;
			return thread.getSessionId();
		}
		throw new IllegalStateException("Method must be invoked within a remote-call-implementation!");
	}

	/**
	 * Sets a custom invoke timeout for a remote method
	 *
	 * @param method  Method for which the custom timeout should be set
	 * @param timeout timeout in milliseconds. A value &lt;= 0 resets to default.
	 */
	public static void setCustomInvokeTimeout(Method method, int timeout) {
		if (timeout > 0) {
			customInvokeTimeoutMap.put(method, timeout);
		} else {
			customInvokeTimeoutMap.remove(method);
		}
	}

	/**
	 * Returns custom invoke timeout for specific remote method.
	 *
	 * @param method remote method
	 * @return value &gt; 0 defines custome timeout in milliseconds, value &lt;=0 defines default timeout
	 */
	static int getCustomInvokeTimeout(Method method) {
		Integer customTimeout = customInvokeTimeoutMap.get(method);
		if (customTimeout == null || customTimeout.intValue() <= 0)
			return 0;

		return customTimeout.intValue();
	}
}