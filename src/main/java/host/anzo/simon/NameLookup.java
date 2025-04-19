/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package host.anzo.simon;

import host.anzo.simon.codec.messages.MsgNameLookupReturn;
import host.anzo.simon.exceptions.EstablishConnectionFailed;
import host.anzo.simon.exceptions.LookupFailedException;
import host.anzo.simon.ssl.SslContextFactory;
import host.anzo.simon.utils.SimonClassLoaderHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.session.IoSession;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @author ACHR
 */
@Slf4j
public class NameLookup extends AbstractLookup {
	private final InetAddress serverAddress;
	private final int serverPort;
	private SslContextFactory sslContextFactory;
	private SimonProxyConfig proxyConfig;
	private ClassLoader classLoader;

	protected NameLookup(String host, int port) throws UnknownHostException {
		this(InetAddress.getByName(host), port);
	}

	protected NameLookup(InetAddress serverAddress, int serverPort) {
		this.serverAddress = serverAddress;
		this.serverPort = serverPort;
	}

	@Override
	public Object lookup(String remoteObjectName) throws LookupFailedException, EstablishConnectionFailed {

		log.debug("begin");

		if (remoteObjectName == null) {
			throw new IllegalArgumentException("Argument cannot be null");
		}

		if (remoteObjectName.isEmpty()) {
			throw new IllegalArgumentException("Argument is not a valid remote object name");
		}

		// check if there is already an dispatcher and key for THIS server
		SessionDispatcherContainer sessionDispatcherContainer = buildSessionDispatcherContainer(remoteObjectName, serverAddress, serverPort, sslContextFactory, proxyConfig);

		Dispatcher dispatcher = sessionDispatcherContainer.dispatcher();
		IoSession session = sessionDispatcherContainer.session();
		/*
		 * Create array with interfaces the proxy should have
		 * first contact server for lookup of interfaces
		 * --> this request blocks!
		 */
		MsgNameLookupReturn msg = dispatcher.invokeNameLookup(session, remoteObjectName);

		if (msg.hasError()) {

			log.trace("Lookup failed. Releasing dispatcher.");
			releaseDispatcher(dispatcher);
			throw new LookupFailedException(msg.getErrorMsg());
		} else {

			Class<?>[] listenerInterfaces;
			try {
				listenerInterfaces = (classLoader == null ? msg.getInterfaces() : msg.getInterfaces(classLoader));
			} catch (ClassNotFoundException ex) {
				throw new LookupFailedException("Not able to load remote interfaces. Maybe you need to specify a specific classloader via Lookup#setClassLoader()?", ex);
			}

			for (Class<?> class1 : listenerInterfaces) {
				log.debug("iface: {}", class1.getName());
			}

			/*
			 * Creates proxy for method-call-forwarding to server
			 */
			SimonProxy handler = new SimonProxy(dispatcher, session, remoteObjectName, listenerInterfaces, true);
			log.trace("proxy created");

			/*
			 * Create the proxy-object with the needed interfaces
			 */
			Object proxy = Proxy.newProxyInstance(SimonClassLoaderHelper.getClassLoader(Simon.class, classLoader), listenerInterfaces, handler);
			log.debug("end");
			return proxy;
		}
	}

	@Override
	public SslContextFactory getSslContextFactory() {
		return sslContextFactory;
	}

	@Override
	public void setSslContextFactory(SslContextFactory sslContextFactory) {
		this.sslContextFactory = sslContextFactory;
	}

	@Override
	public SimonProxyConfig getProxyConfig() {
		return proxyConfig;
	}

	@Override
	public void setProxyConfig(SimonProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
	}

	@Override
	public ClassLoader getClassLoader() {
		return classLoader;
	}

	@Override
	public void setClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public InetAddress getServerAddress() {
		return serverAddress;
	}

	@Override
	public int getServerPort() {
		return serverPort;
	}
}
