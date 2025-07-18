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

import host.anzo.simon.exceptions.SessionException;
import host.anzo.simon.exceptions.SimonRemoteException;
import host.anzo.simon.utils.SimonClassLoaderHelper;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.session.IoSession;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;

/**
 * The InvocationHandler which redirects each method call over the network to
 * the related dispatcher
 *
 * @author achristian
 */
@Slf4j
public class SimonProxy implements InvocationHandler {
	/**
	 * name of the corresponding remote object in the remote lookup table
	 */
	private String remoteObjectName;

	/**
	 * a reference to the associated dispatcher
	 */
	private Dispatcher dispatcher;

	/**
	 * a reference to the session which is the reference to the related network
	 * connection
	 */
	private final IoSession session;

	/**
	 * the interfaces that the remote object has exported
	 */
	Class<?>[] remoteInterfaces;

	/**
	 * flag that indicates whether this simonproxy has been created via regular
	 * lookup, or during runtime as callback
	 */
	private final boolean regularLookup;

	/**
	 * Constructor which sets the reference to the dispatcher and the remote
	 * object name
	 *
	 * @param dispatcher       a reference to the underlying dispatcher
	 * @param session          a reference to the {@link IoSession} of the corresponding
	 *                         network connection
	 * @param remoteObjectName name of the remote object
	 * @param remoteInterfaces the interfaces that the remote object has
	 *                         exported
	 * @param regularLookup
	 */
	protected SimonProxy(Dispatcher dispatcher, IoSession session, String remoteObjectName, Class<?>[] remoteInterfaces, boolean regularLookup) {
		this.dispatcher = dispatcher;
		this.session = session;

		this.remoteObjectName = remoteObjectName;
		this.remoteInterfaces = remoteInterfaces;
		this.regularLookup = regularLookup;

		// register phantom reference for releasing remote object on gc'ed proxy object
		// only for callbacks!
		if (remoteObjectName.startsWith(SimonRemoteInstance.PREFIX)) {
			dispatcher.getRefQueue().addRef(this);
		}
	}

	/**
	 * @see InvocationHandler#invoke(Object,
	 * Method, Object[])
	 */
	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		log.debug("begin");

		if (dispatcher == null) {
			log.debug("Hey, you cannot use an already closed connection ... s&h§/&*$$");
			log.debug("end");
			throw new SimonRemoteException(
					"Cannot invoke method " + method.getName() + ". Connection to server is already closed.");
		}

		// trace all the given arguments
		if (log.isTraceEnabled()) {
			log.trace("method={} argsLength={}", method.getName(), (args == null ? 0 : args.length));
			if (args != null) {
				for (int i = 0; i < args.length; i++) {
					log.trace("args[{}]={}", i, (args[i] instanceof Proxy ? Simon.getSimonProxy(args[i]).getDetailString() : args[i]));
				}
			} else {
				log.trace("args=null");
			}
		}

		try {
			// redirect invocation
			if (method.toString().equalsIgnoreCase(Statics.EQUALS_METHOD_SIGNATURE)) {

				Object o = args[0];
				log.debug("Checking for SimonProxy as argument ...");
				if (Utils.isSimonProxy(o)) {
					log.debug("It's a SimonProxy instance");
					// if one tries to compare this proxy with another proxy: Do it locally here by comparing the string representation
					//                    return o.toString().equals(this.toString());
					o = new SimonRemoteInstance(session, args[0]);
					log.debug("Given argument is a SimonProxy, created SimonRemoteInstance: {}", (SimonRemoteInstance) o);
				} else { // else, it's a standard object -> check for serializeable and do a remote-equals ...
					log.debug("It's a standard object");
					// .. and if the standard object is not serializable, throw an exception
					if (o != null && !(o instanceof Serializable)) {
						throw new IllegalArgumentException("SIMON remote objects can only compared with objects that are serializable!");
					}
				}
				return remoteEquals(o);
			} else if (method.toString().equalsIgnoreCase(Statics.HASHCODE_METHOD_SIGNATURE)) {
				try {
					return remoteHashCode();
				} catch (SimonRemoteException e) {
					shutdownServerConnection(method);
					throw new SimonRemoteException(e.getMessage());
				}
			} else if (method.toString().equalsIgnoreCase(Statics.TOSTRING_METHOD_SIGNATURE)) {
				return remoteToString();
			}
		} catch (IOException e) {
			throw new SimonRemoteException(
					"Could not process invocation of method '" + method.getName() + "'. Underlying exception: " + e);
		}

		if (method.getReturnType() == void.class) {
			log.debug("Detected void return type for method: {}. Sending async.", method.getName());
			try {
				dispatcher.sendAsyncInvoke(session, remoteObjectName, method, args);
				log.debug("Sent async void method call: {}", method.getName());
			} catch (Exception e) {
				log.error("Failed to send async void method call {} for {}", method.getName(), remoteObjectName, e);
				if (e instanceof SimonRemoteException) {
					throw e;
				}
			}
			log.debug("end (async void)");
			return null;
		}

		log.debug("Invoking remote method expecting result: {}", method.getName());

		/*
		 * server then does the following:
		 * server gets according to the method name and parameter types the method
		 * and invokes the method. the result is communicated back to the client
		 */
		Object result = dispatcher.invokeMethod(session, remoteObjectName, method, args);

		// Check for exceptions ...
		if (result instanceof Throwable) {
			log.debug("return value: {}", result);
			if (result instanceof SimonRemoteException) {
				shutdownServerConnection(method);
			} else {
				log.debug("Forwarding exception to application: {}", ((Throwable) result).getMessage());
			}
			throw (Throwable) result;
		}

		if (result instanceof SimonEndpointReference) {

			SimonEndpointReference ser = (SimonEndpointReference) result;
			result = dispatcher.getLookupTable().getRemoteObjectContainer(ser.getRemoteObjectName()).getRemoteObject();
			log.debug("Result of method {} is a {}. Injecting original object: {}", new Object[]{
					method,
					ser,
					result
			});
		} else if (result instanceof SimonRemoteInstance) {

			// creating a proxy for the callback
			SimonRemoteInstance simonCallback = (SimonRemoteInstance) result;

			List<String> interfaceNames = simonCallback.getInterfaceNames();
			Class<?>[] listenerInterfaces = new Class<?>[interfaceNames.size()];
			for (int j = 0; j < interfaceNames.size(); j++) {
				listenerInterfaces[j] = Class.forName(interfaceNames.get(j), true, dispatcher.getClassLoader());
			}

			SimonProxy handler = new SimonProxy(dispatcher, session, simonCallback.getId(), new Class<?>[]{}, false);

			// reimplant the proxy object
			result = Proxy.newProxyInstance(SimonClassLoaderHelper.getClassLoader(this.getClass()), listenerInterfaces, handler);
		}

		log.debug("end");


		return result;
	}

	private void shutdownServerConnection(Method method) {
		log.error("Problematic error while invoking '{}#{}'. Shutting down server connection.", remoteObjectName, method);
		AbstractLookup.releaseDispatcher(dispatcher);
		dispatcher = null;
	}

	/**
	 * Returns the {@link SocketAddress} of the remote host connected with this
	 * proxy
	 *
	 * @return the {@link SocketAddress} of the remote host
	 */
	protected SocketAddress getRemoteSocketAddress() {
		return session.getRemoteAddress();
	}

	/**
	 * Returns the {@link SocketAddress} of the local host connected with this
	 * proxy
	 *
	 * @return the {@link SocketAddress} of the local host
	 */
	protected SocketAddress getLocalSocketAddress() {
		return session.getLocalAddress();
	}

	/**
	 * Redirects the toString() call to the remote host to be called there. The
	 * result is a String in the format:<br>
	 * <pre>
	 * [Proxy={name of the remote object}|invocationHandler={result of proxy's super.toString()}|remote={result of remote toString() call}]
	 * </pre>
	 *
	 * @return the result of the remote "toString()" call
	 * @throws SimonRemoteException
	 * @throws SimonRemoteException
	 */
	private String remoteToString() throws SimonRemoteException {
		return "[Proxy=" + remoteObjectName + "|invocationHandler=" + super.toString() + "|remote=" +
				dispatcher.invokeToString(session, remoteObjectName) + "|interfaces=" +
				Arrays.toString(remoteInterfaces) + "|sessionId=" + Utils.longToHexString(session.getId()) + "]";
	}

	/**
	 * Redirects hashCode() method call to the remote host and returns his
	 * result
	 *
	 * @return the result of the remote hashCode() call
	 * @throws IOException
	 */
	private int remoteHashCode() throws SimonRemoteException {
		return dispatcher.invokeHashCode(session, remoteObjectName);
	}

	/**
	 * Redirects hashEquals() method call to the remote host and returns his
	 * result
	 *
	 * @param object the object to compare with
	 * @return the result of the remote equals() call
	 * @throws IOException
	 */
	private boolean remoteEquals(Object object) throws IOException {
		return dispatcher.invokeEquals(session, remoteObjectName, object);
	}

	/**
	 * Releases this proxy. This cancels also the session on the
	 * {@link Dispatcher}.
	 *
	 * @return the {@link Dispatcher} related to this proxy.
	 */
	protected Dispatcher release() {
		log.debug("setting remoteobjectname to null and return dispatcher");
		remoteObjectName = null;
		return dispatcher;
	}

	@Override
	public String toString() {
		try {
			return remoteToString();
		} catch (SimonRemoteException e) {
			return "Error occured while invoking " + remoteObjectName + "#toString(). Error was: " + e.getMessage();
		}
	}

	protected String getDetailString() {
		log.debug("Getting detail string ...");
		String detail = "[Proxy=" + remoteObjectName + "|invocationHandler=" + super.toString() + "|sessionId=" +
				Utils.longToHexString(session.getId()) + "]";
		log.debug("Detail string is: {}", detail);
		return detail;
	}

	/**
	 * Returns the proxy's remote object name in the related lookup table
	 *
	 * @return the remote object name
	 */
	protected String getRemoteObjectName() {
		return remoteObjectName;
	}

	/**
	 * Returns the {@link IoSession} related to this proxy
	 *
	 * @return an instance of {@link IoSession}
	 */
	protected IoSession getIoSession() {
		return session;
	}

	/**
	 * @return {@link IoSession} session ID
	 */
	public long getSessionId() {
		return session.getId();
	}

	/**
	 * Returns the {@link Dispatcher} instance related to this proxy. May return
	 * null in case of an already shutdown session
	 *
	 * @return an instance of {@link Dispatcher}
	 */
	protected Dispatcher getDispatcher() {
		return dispatcher;
	}

	/**
	 * Returns true if this proxy has been cerated in context of a lookup-call.
	 * False in case of callback object
	 *
	 * @return boolean
	 */
	protected boolean isRegularLookup() {
		return regularLookup;
	}
}
