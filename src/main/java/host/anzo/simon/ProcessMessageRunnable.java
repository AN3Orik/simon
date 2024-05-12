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

import host.anzo.simon.codec.messages.*;
import host.anzo.simon.exceptions.*;
import host.anzo.simon.utils.SimonClassLoaderHelper;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.future.CloseFuture;
import org.apache.mina.core.session.IoSession;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * This class is feed with all kind of messages (requests/invokes and returns)
 * and is then run on a thread pool.
 * <p>
 * The message gets then processed and answered. Either ProcessMessageRunnable
 * invokes the requested method and returns the result to the remote, or it
 * passes the result to the dispatcher where then the requesting call is getting
 * answered.
 *
 * @author achr
 */
@Slf4j
public class ProcessMessageRunnable implements Runnable {
	private final AbstractMessage abstractMessage;
	private final IoSession session;
	private final Dispatcher dispatcher;

	protected ProcessMessageRunnable(Dispatcher dispatcher, IoSession session, AbstractMessage abstractMessage) {
		this.dispatcher = dispatcher;
		this.session = session;
		this.abstractMessage = abstractMessage;
	}

	@Override
	public void run() {

		log.debug("ProcessMessageRunnable: {} on sessionId {}", abstractMessage, Utils.longToHexString(session.getId()));

		ProcessMessageThread currentThread = (ProcessMessageThread) Thread.currentThread();
		currentThread.setSessionId(session.getId());

		int msgType = abstractMessage.getMsgType();

		switch (msgType) {

			case SimonMessageConstants.MSG_NAME_LOOKUP:
				processNameLookup();
				break;

			case SimonMessageConstants.MSG_INTERFACE_LOOKUP:
				processInterfaceLookup();
				break;

			case SimonMessageConstants.MSG_NAME_LOOKUP_RETURN:
				processNameLookupReturn();
				break;

			case SimonMessageConstants.MSG_INTERFACE_LOOKUP_RETURN:
				processInterfaceLookupReturn();
				break;

			case SimonMessageConstants.MSG_INVOKE:
				processInvoke();
				break;

			case SimonMessageConstants.MSG_INVOKE_RETURN:
				processInvokeReturn();
				break;

			case SimonMessageConstants.MSG_TOSTRING:
				processToString();
				break;

			case SimonMessageConstants.MSG_TOSTRING_RETURN:
				processToStringReturn();
				break;

			case SimonMessageConstants.MSG_EQUALS:
				processEquals();
				break;

			case SimonMessageConstants.MSG_EQUALS_RETURN:
				processEqualsReturn();
				break;

			case SimonMessageConstants.MSG_HASHCODE:
				processHashCode();
				break;

			case SimonMessageConstants.MSG_HASHCODE_RETURN:
				processHashCodeReturn();
				break;

			case SimonMessageConstants.MSG_OPEN_RAW_CHANNEL:
				processOpenRawChannel();
				break;

			case SimonMessageConstants.MSG_OPEN_RAW_CHANNEL_RETURN:
				processOpenRawChannelReturn();
				break;

			case SimonMessageConstants.MSG_CLOSE_RAW_CHANNEL:
				processCloseRawChannel();
				break;

			case SimonMessageConstants.MSG_CLOSE_RAW_CHANNEL_RETURN:
				processCloseRawChannelReturn();
				break;

			case SimonMessageConstants.MSG_RAW_CHANNEL_DATA:
				processRawChannelData();
				break;

			case SimonMessageConstants.MSG_RAW_CHANNEL_DATA_RETURN:
				processRawChannelDataReturn();
				break;

			case SimonMessageConstants.MSG_PING:
				processPing();
				break;

			case SimonMessageConstants.MSG_PONG:
				processPong();
				break;

			case SimonMessageConstants.MSG_ERROR:
				processError();
				break;

			case SimonMessageConstants.MSG_RELEASE_REF:
				processReleaseRef();
				break;

			default:
				// FIXME what to do here ?!
				log.error("ProcessMessageRunnable: msgType={} not supported! terminating...", msgType);
				System.exit(1);
				break;
		}
	}

	private void processRawChannelDataReturn() {
		log.debug("begin");

		log.debug("processing MsgRawChannelDataReturn...");
		MsgRawChannelDataReturn msg = (MsgRawChannelDataReturn) abstractMessage;
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);

		log.debug("put result to queue={}", msg);

		log.debug("end");
	}

	private void processPing() {
		log.debug("begin");
		log.debug("processing MsgPing...");

		log.debug("replying pong");
		try {
			dispatcher.sendPong(session);
		} catch (SessionException e) {
			log.warn("could not reply pong for seqId {}. Error was: {}", abstractMessage.getSequence(), e.getMessage());
		}

		log.debug("end");
	}

	private void processPong() {
		log.debug("begin");
		log.debug("processing MsgPong...");

		dispatcher.getPingWatchdog().notifyPongReceived(session);
		log.debug("end");
	}

	private void processOpenRawChannel() {
		log.debug("begin");

		log.debug("processing MsgOpenRawChannel...");
		MsgOpenRawChannel msg = (MsgOpenRawChannel) abstractMessage;

		MsgOpenRawChannelReturn returnMsg = new MsgOpenRawChannelReturn();
		returnMsg.setSequence(msg.getSequence());
		returnMsg.setReturnValue(dispatcher.isRawChannelDataListenerRegistered(msg.getChannelToken()));
		session.write(returnMsg);

		log.debug("end");
	}

	private void processOpenRawChannelReturn() {
		log.debug("begin");
		log.debug("processing MsgOpenRawChannelReturn...");
		MsgOpenRawChannelReturn msg = (MsgOpenRawChannelReturn) abstractMessage;
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);
		log.debug("put result to queue={}", msg);
		log.debug("end");
	}

	private void processCloseRawChannel() {
		MsgCloseRawChannelReturn returnMsg = new MsgCloseRawChannelReturn();
		try {
			log.debug("begin");

			log.debug("processing MsgCloseRawChannel...");
			MsgCloseRawChannel msg = (MsgCloseRawChannel) abstractMessage;

			dispatcher.unprepareRawChannel(msg.getChannelToken());

			returnMsg.setSequence(msg.getSequence());
			returnMsg.setReturnValue(true);
		} catch (RawChannelException ex) {
			log.warn("Error occured during RawChannelDataListener#close()", ex);
			returnMsg.setErrorMsg(ex.getMessage());
		} finally {
			session.write(returnMsg);
			log.debug("end");
		}
	}

	private void processCloseRawChannelReturn() {
		log.debug("begin");
		log.debug("processing MsgCloseRawChannelReturn...");
		MsgCloseRawChannelReturn msg = (MsgCloseRawChannelReturn) abstractMessage;
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);
		log.debug("put result to queue={}", msg);
		log.debug("end");
	}

	private void processRawChannelData() {
		MsgRawChannelDataReturn returnMsg = new MsgRawChannelDataReturn();
		try {
			log.debug("begin");

			log.debug("processing MsgRawChannelData...");
			MsgRawChannelData msg = (MsgRawChannelData) abstractMessage;

			RawChannelDataListener rawChannelDataListener = dispatcher.getRawChannelDataListener(msg.getChannelToken());
			if (rawChannelDataListener != null) {
				log.debug("writing data to {} for token {}.", rawChannelDataListener, msg.getChannelToken());
				ByteBuffer data = msg.getData();
				data.flip();
				rawChannelDataListener.write(data);
				log.debug("data forwarded to listener for token {}", msg.getChannelToken());
				returnMsg.setSequence(msg.getSequence());
			} else {
				log.error("trying to forward data to a not registered or already closed listener: token={} data={}", msg.getChannelToken(), msg.getData());
			}
		} catch (RawChannelException ex) {
			log.warn("Error occured during RawChannelDataListener#write()", ex);
			returnMsg.setErrorMsg(ex.getMessage());
		} finally {
			session.write(returnMsg);
			log.debug("end");
		}
	}

	/**
	 * processes a name lookup
	 */
	private void processNameLookup() {
		log.debug("begin");

		log.debug("processing MsgLookup...");
		MsgNameLookup msg = (MsgNameLookup) abstractMessage;
		String remoteObjectName = msg.getRemoteObjectName();

		log.debug("Sending result for remoteObjectName={}", remoteObjectName);

		MsgNameLookupReturn ret = new MsgNameLookupReturn();
		ret.setSequence(msg.getSequence());
		try {

			Object remoteObject = dispatcher.getLookupTable().getRemoteObjectContainer(remoteObjectName).getRemoteObject();
			SimonRemoteMarker marker = Utils.getMarker(remoteObject);
			String[] interfaceNames;
			if (marker != null) {
				RemoteObjectContainer container = dispatcher.getLookupTable().getRemoteObjectContainer(remoteObjectName);

				Class<?>[] interfaces = null;
				interfaces = container.getRemoteObjectInterfaces();
				interfaceNames = new String[interfaces.length];
				for (int i = 0; i < interfaceNames.length; i++) {
					//                    interfaceNames[i] = interfaces[i].getCanonicalName();
					interfaceNames[i] = interfaces[i].getName();
				}
			} else {
				Class<?>[] interfaces = null;
				interfaces = Utils.findAllRemoteInterfaces(dispatcher.getLookupTable().getRemoteObjectContainer(remoteObjectName).getRemoteObject().getClass());

				interfaceNames = new String[interfaces.length];
				for (int i = 0; i < interfaceNames.length; i++) {
					//                    interfaceNames[i] = interfaces[i].getCanonicalName();
					interfaceNames[i] = interfaces[i].getName();
				}
			}
			ret.setInterfaces(interfaceNames);
		} catch (LookupFailedException e) {
			log.debug("Lookup for remote object '{}' failed: {}", remoteObjectName, e.getMessage());
			ret.setErrorMsg("Error: " + e.getClass() + "->" + e.getMessage() + "\n" + Utils.getStackTraceAsString(e));
		}
		session.write(ret);

		log.debug("end");
	}

	/**
	 * processes a interface lookup
	 */
	private void processInterfaceLookup() {
		log.debug("begin");

		log.debug("processing MsgInterfaceLookup...");
		MsgInterfaceLookup msg = (MsgInterfaceLookup) abstractMessage;
		String canonicalInterfaceName = msg.getCanonicalInterfaceName();

		log.debug("Sending result for interfaceName={}", canonicalInterfaceName);

		MsgInterfaceLookupReturn ret = new MsgInterfaceLookupReturn();
		ret.setSequence(msg.getSequence());
		try {

			RemoteObjectContainer container = dispatcher.getLookupTable().getRemoteObjectContainerByInterface(canonicalInterfaceName);

			Class<?>[] interfaces = container.getRemoteObjectInterfaces();
			String[] interfaceNames = new String[interfaces.length];
			for (int i = 0; i < interfaceNames.length; i++) {
				interfaceNames[i] = interfaces[i].getCanonicalName();
			}

			ret.setInterfaces(interfaceNames);
			ret.setRemoteObjectName(container.getRemoteObjectName());
		} catch (LookupFailedException e) {
			log.debug("Lookup for remote object '{}' failed: {}", canonicalInterfaceName, e.getMessage());
			ret.setErrorMsg("Error: " + e.getClass() + "->" + e.getMessage() + "\n" + Utils.getStackTraceAsString(e));
		}
		session.write(ret);

		log.debug("end");
	}

	private void processNameLookupReturn() {
		log.debug("begin");

		log.debug("processing MsgNameLookupReturn...");
		MsgNameLookupReturn msg = (MsgNameLookupReturn) abstractMessage;

		log.debug("Forward result to waiting monitor");
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);

		log.debug("end");
	}

	private void processInterfaceLookupReturn() {
		log.debug("begin");

		log.debug("processing MsgInterfaceLookupReturn...");
		MsgInterfaceLookupReturn msg = (MsgInterfaceLookupReturn) abstractMessage;

		log.debug("Forward result to waiting monitor");
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);

		log.debug("end");
	}

	/**
	 * This method is processed on the remote end (where the object to call
	 * lives) that finally calls the method and returns the result to the
	 * calling end.
	 */
	private void processInvoke() {
		log.debug("begin");

		log.debug("processing MsgInvoke...");

		Object result = null;

		MsgInvoke msg = (MsgInvoke) abstractMessage;

		// if received msg has an error
		if (msg.hasError()) {
			result = new SimonRemoteException(
					"Received MsgInvoke had errors. Cannot process invocation. error msg: " + msg.getErrorMsg());

			MsgInvokeReturn returnMsg = new MsgInvokeReturn();
			returnMsg.setSequence(msg.getSequence());

			returnMsg.setReturnValue(result);

			log.debug("Sending result={}", returnMsg);

			session.write(returnMsg);
			log.debug("end");
			return;
		}

		Method method = msg.getMethod();
		Object[] arguments = msg.getArguments();
		String remoteObjectName = msg.getRemoteObjectName();

		try {

			// ------------
			// replace existing SimonRemote objects with proxy object
			if (arguments != null) {
				try {
					for (int i = 0; i < arguments.length; i++) {

						// search the arguments for remote endpoint references
						if (arguments[i] instanceof SimonEndpointReference ser) {
							log.debug("SimonEndpointReference in args found: {}", ser);
							arguments[i] = dispatcher.getLookupTable().getRemoteObjectContainer(ser.getRemoteObjectName()).getRemoteObject();
							log.debug("Original object for SimonEndpointReference injected: " + arguments[i]);
						}

						// search the arguments for remote instances
						if (arguments[i] instanceof SimonRemoteInstance simonCallback) {

							log.debug("SimonCallback in args found. id={}", simonCallback.getId());

							List<String> interfaceNames = simonCallback.getInterfaceNames();
							Class<?>[] listenerInterfaces = new Class<?>[interfaceNames.size()];
							for (int j = 0; j < interfaceNames.size(); j++) {
								// See: http://dev.root1.de/issues/127
								listenerInterfaces[j] = Class.forName(interfaceNames.get(j), true, dispatcher.getClassLoader());
							}

							// re-implant the proxy object
							SimonProxy simonProxy = new SimonProxy(dispatcher, session, simonCallback.getId(), listenerInterfaces, false);
							arguments[i] = Proxy.newProxyInstance(SimonClassLoaderHelper.getClassLoader(this.getClass()), listenerInterfaces, simonProxy);
							log.debug("proxy object for SimonCallback injected");
						}
					}
				} catch (ClassNotFoundException ex) {
					throw new ClassNotFoundException(
							"Callback interface class(es) not found with classloader [" + dispatcher.getClassLoader() +
									"].", ex);
				}
			}
			// ------------

			log.debug("ron={} method={} args={}", remoteObjectName, method, arguments);

			Object remoteObject = dispatcher.getLookupTable().getRemoteObjectContainer(remoteObjectName).getRemoteObject();

			try {
				result = method.invoke(remoteObject, arguments);
			} catch (IllegalArgumentException ex) {
				log.error(
						"IllegalArgumentException while invoking remote method. Arguments obviously do not match the methods parameter types. Errormsg: " +
								ex.getMessage());
				log.error("***** Analysis of arguments and paramtypes ... ron={} method={} ", remoteObjectName, method.getName());
				if (arguments != null && arguments.length != 0) {
					for (int i = 0; i < arguments.length; i++) {
						log.error("***** arguments[" + i + "]: " +
								(arguments[i] == null ? "null" : arguments[i].getClass().getCanonicalName()) +
								" toString: " + (arguments[i] == null ? "null" : arguments[i].toString()));
					}
				} else {
					log.error("***** no arguments available.");
				}

				Class<?>[] paramType = method.getParameterTypes();
				if (paramType != null && paramType.length != 0) {
					for (int i = 0; i < paramType.length; i++) {
						log.error("***** paramType[" + i + "]: " +
								(paramType[i] == null ? "null" : paramType[i].getCanonicalName()));
					}
				} else {
					log.error("***** no paramtypes available.");
				}

				for (Method m : remoteObject.getClass().getMethods()) {
					log.error("***** remoteObject '{}' has method: {}", remoteObjectName, m);
				}

				log.error("***** method signature: {}", method.toString());
				log.error("***** generic method signature: {}", method.toGenericString());
				log.error("***** Error stacktrace:\n{}", Utils.getStackTraceAsString(ex));
				log.error("***** Analysis of arguments and paramtypes ... *DONE*");
				throw ex;
			}

			// check for re-transmitting callback
			if (Utils.isSimonProxy(result)) {

				SimonProxy sp = Simon.getSimonProxy(result);
				SimonEndpointReference ser = new SimonEndpointReference(sp);
				log.debug("Result of method is SimonProxy/Local Endpoint. Sending: {}", ser);
				result = ser;
				//                throw new SimonException("Result of method '" + method + "' is a local endpoint of a remote object. Endpoints can not be transferred.");
			}

			// check for normal remote objects?!
			if (dispatcher.getLookupTable().isSimonRemoteRegistered(result)) {
				throw new SimonException("Result '" + result + "' of method '" + method +
						"' is a registered remote object. Endpoints can not be transferred.");
			}

			if (method.getReturnType() == void.class) {
				result = new SimonVoid();
			}

			// register "SimonCallback"-results in lookup-table
			if (Utils.isValidRemote(result)) {

				log.debug("Result of method '{}' is SimonRemote: {}", method, result);

				SimonRemoteInstance sri = new SimonRemoteInstance(session, result);

				dispatcher.getLookupTable().putRemoteInstance(session.getId(), sri, result);
				result = sri;
			}
		} catch (IllegalArgumentException e) {
			result = e;
		} catch (InvocationTargetException e) {
			if (e.getTargetException() instanceof UndeclaredThrowableException) {
				result = Utils.getRootCause(e.getTargetException());
			} else {
				result = e.getTargetException();
			}
		} catch (Exception e) {
			SimonRemoteException sre = new SimonRemoteException(
					"Errow while invoking '" + remoteObjectName + "#" + method + "' due to underlying exception: " +
							e.getClass());
			sre.initCause(e);
			result = sre;
		}

		// a return value can be "null" ... this has to be serialized to the client
		if (result != null && !(result instanceof Serializable)) {
			log.warn("Result '{}' is not serializable", result);
			result = new SimonRemoteException("Result of method '" + method +
					"' must be serializable and therefore implement 'java.io.Serializable' or 'host.anzo.simon.SimonRemote'");
		}

		MsgInvokeReturn returnMsg = new MsgInvokeReturn();
		returnMsg.setSequence(msg.getSequence());

		returnMsg.setReturnValue(result);

		log.debug("Sending result={}", returnMsg);

		session.write(returnMsg);
		log.debug("end");
	}

	/**
	 * This method is triggered on caller end to retrieve the invocation result,
	 * pass it to the result map and wake the caller thread
	 */
	private void processInvokeReturn() {
		log.debug("begin");

		log.debug("processing MsgInvokeReturn...");
		MsgInvokeReturn msg = (MsgInvokeReturn) abstractMessage;
		log.debug("put result to queue={}", msg);
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);

		log.debug("end");
	}

	private void processToString() {
		log.debug("begin");

		log.debug("processing MsgToString...");
		MsgToString msg = (MsgToString) abstractMessage;

		String remoteObjectName = msg.getRemoteObjectName();
		String returnValue = null;
		try {
			returnValue = dispatcher.getLookupTable().getRemoteObjectContainer(remoteObjectName).getRemoteObject().toString();
		} catch (LookupFailedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		MsgToStringReturn returnMsg = new MsgToStringReturn();
		returnMsg.setSequence(msg.getSequence());
		returnMsg.setReturnValue(returnValue);
		session.write(returnMsg);
		log.debug("end");
	}

	private void processToStringReturn() {
		log.debug("begin");
		log.debug("processing MsgToStringReturn...");
		MsgToStringReturn msg = (MsgToStringReturn) abstractMessage;
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);

		log.debug("put result to queue={}", msg);

		log.debug("end");
	}

	private void processEquals() {
		log.debug("begin");

		log.debug("processing MsgEquals...");
		MsgEquals msg = (MsgEquals) abstractMessage;

		String remoteObjectName = msg.getRemoteObjectName();
		Object objectToCompareWith = msg.getObjectToCompareWith();

		boolean equalsResult = false;
		try {
			if (objectToCompareWith instanceof SimonRemoteInstance sri) {
				log.debug("Got a SimonRemoteInstance(ron='{}') to compare with, looking for real object...", sri.getRemoteObjectName());
				objectToCompareWith = dispatcher.getLookupTable().getRemoteObjectContainer(sri.getRemoteObjectName()).getRemoteObject();
			}

			Object tthis = dispatcher.getLookupTable().getRemoteObjectContainer(remoteObjectName).getRemoteObject();
			if (objectToCompareWith == null) {
				equalsResult = false;
			} else {
				equalsResult = tthis.equals(objectToCompareWith);
			}
			log.debug("this='{}' objectToCompareWith='{}' equalsResult={}", tthis.toString(), (
					objectToCompareWith == null ? "NULL" : objectToCompareWith.toString()), equalsResult);
		} catch (LookupFailedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		MsgEqualsReturn returnMsg = new MsgEqualsReturn();
		returnMsg.setSequence(msg.getSequence());
		returnMsg.setEqualsResult(equalsResult);
		session.write(returnMsg);
		log.debug("end");
	}

	private void processEqualsReturn() {
		log.debug("begin");
		log.debug("processing MsgEqualsReturn...");
		MsgEqualsReturn msg = (MsgEqualsReturn) abstractMessage;
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);

		log.debug("put result to queue={}", msg);

		log.debug("end");
	}

	private void processHashCode() {
		log.debug("begin");

		log.debug("processing MsgHashCode...");
		MsgHashCode msg = (MsgHashCode) abstractMessage;

		String remoteObjectName = msg.getRemoteObjectName();

		MsgHashCodeReturn returnMsg = new MsgHashCodeReturn();
		returnMsg.setSequence(msg.getSequence());

		int returnValue = -1;
		try {
			returnValue = dispatcher.getLookupTable().getRemoteObjectContainer(remoteObjectName).getRemoteObject().hashCode();
		} catch (LookupFailedException e) {
			returnMsg.setErrorMsg(
					"Failed looking up the remote object for getting the hash code. Error: " + e.getMessage() + "\n" +
							Utils.getStackTraceAsString(e));
		}

		returnMsg.setReturnValue(returnValue);
		session.write(returnMsg);
		log.debug("end");
	}

	private void processHashCodeReturn() {
		log.debug("begin");
		log.debug("processing MsgHashCodeReturn...");
		MsgHashCodeReturn msg = (MsgHashCodeReturn) abstractMessage;
		dispatcher.putResultToQueue(session, msg.getSequence(), msg);

		log.debug("put result to queue={}", msg);

		log.debug("end");
	}

	private void processError() {
		log.debug("begin");

		log.debug("processing MsgError...");
		MsgError msg = (MsgError) abstractMessage;

		String remoteObjectName = msg.getRemoteObjectName();
		String errorMessage = msg.getErrorMessage();
		Throwable throwable = msg.getThrowable();
		boolean isDecodeError = msg.isDecodeError();

		String exceptionMessage = "";

		// if error happened on the local while reading a message
		if (isDecodeError) {
			if (remoteObjectName != null && remoteObjectName.length() > 0) {
				exceptionMessage = "An error occured while reading a message for remote object '" + remoteObjectName +
						"'. Error message: " + errorMessage;
			} else {
				exceptionMessage = "An error occured while reading a message. Error message: " + errorMessage;
			}
			// if error happened on remote while writing a message
		} else {
			if (remoteObjectName != null && remoteObjectName.length() > 0) {
				exceptionMessage =
						"An error occured on remote while writing a message to remote object '" + remoteObjectName +
								"'. Error message: " + errorMessage;
			} else {
				exceptionMessage = "An error occured on remote while writing a message. Error message: " + errorMessage;
			}
		}

		SimonException se = new SimonException(exceptionMessage);
		se.initCause(throwable);
		CloseFuture closeFuture = session.closeNow();
		closeFuture.awaitUninterruptibly();
		log.debug("end");
		throw se;
	}

	private void processReleaseRef() {
		log.debug("begin");

		log.debug("processing MsgReleaseRef...");
		MsgReleaseRef msg = (MsgReleaseRef) abstractMessage;

		log.debug("Removing ref for {} on session {}", msg.getRefId(), session.getId());
		dispatcher.getLookupTable().removeCallbackRef(session.getId(), msg.getRefId());

		log.debug("end");
	}
}
