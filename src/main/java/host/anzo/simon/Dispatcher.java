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

import host.anzo.simon.codec.messages.*;
import host.anzo.simon.exceptions.*;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.FilterEvent;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class is the "brain" of SIMON on server side, as well as on client side.
 * <p>
 * It handles all the I/O and delegates the required tasks
 *
 * @author ACHR
 */
@Slf4j
public class Dispatcher implements IoHandler {
	/**
	 * The table that holds all the registered/bind remote objects
	 */
	private final LookupTable lookupTable;
	/**
	 * a simple counter that is used for creating sequence IDs
	 */
	private final AtomicInteger sequenceIdCounter = new AtomicInteger(0);
	/**
	 * The map that holds the relation between the sequenceID and the received
	 * result. If a request is placed, the map contains the sequenceID and the
	 * corresponding monitor object. If the result is present, the monitor
	 * object is replaced with the result
	 */
	private final Map<Integer, Object> requestMonitorAndResultMap = Collections.synchronizedMap(new HashMap<>());
	/**
	 * This map contains pairs of sessions and list of open requests This is
	 * needed to do a clean shutdown of a session
	 */
	private final Map<IoSession, List<Integer>> sessionHasRequestPlaced = Collections.synchronizedMap(new HashMap<IoSession, List<Integer>>());
	/**
	 * the thread-pool where the worker-threads live in
	 */
	private ExecutorService messageProcessorPool = null;
	/**
	 * Shutdown flag. If set to true, the dispatcher is going to shutdown itself
	 * and all related stuff
	 */
	private boolean shutdownInProgress;
	/**
	 * indicates if the dispatcher is running or not
	 */
	private boolean isRunning;
	/**
	 * an identifier string to determine to which server this dispatcher is
	 * connected to
	 */
	private final String serverString;
	/**
	 * A map that contains the token<->RawChannelDatalisteners relation
	 */
	private final HashMap<Integer, RawChannelDataListener> rawChannelMap = new HashMap<>();
	/**
	 * a list of currently used tokens
	 */
	private final ArrayList<Integer> tokenList = new ArrayList<>();
	/**
	 * the dispatcher's reference to the pingwatchdog
	 */
	private final PingWatchdog pingWatchdog;
	/**
	 * the timout value in milliseconds that is used by PipngWatchdog for
	 * keep-alive check
	 */
	private int pingTimeOut = Statics.DEFAULT_WRITE_TIMEOUT;
	/**
	 * A map containing remote object names and a related list with closed
	 * listeners
	 */
	private final Map<String, List<ClosedListener>> remoteObjectClosedListenersList = Collections.synchronizedMap(new HashMap<>());
	/**
	 * Classloader used to load remote interface classes
	 */
	private final ClassLoader classLoader;
	private boolean released = false;
	private final SimonRefQueue<SimonPhantomRef<?>> simonRefQueue;

	private final AtomicReference<SimonSessionListener> sessionListenerRef = new AtomicReference<>(null);

	/**
	 * Sets a session event listener for this Dispatcher.
	 * This method must be called by the application before or immediately after starting the Registry.
	 * @param listener An implementation of SimonSessionListener, or null to remove.
	 */
	public void setSessionListener(SimonSessionListener listener) {
		this.sessionListenerRef.set(listener);
		log.debug("SimonSessionListener {} registered for Dispatcher {}", listener, this);
	}

	/**
	 * Method used by the PingWatchdog for getting the current ping/keepalive
	 * timeout
	 *
	 * @return the pingTimeOut
	 */
	protected int getPingTimeout() {
		return pingTimeOut;
	}

	/**
	 * Method used by the Registry while setting the keep alive timeout
	 *
	 * @param pingTimeOut the pingTimeOut to set
	 */
	protected void setPingTimeOut(int pingTimeOut) {
		this.pingTimeOut = pingTimeOut;
	}

	/**
	 * Method used by the Loopup-Classes to remove a list of closed listener for
	 * a given remote object
	 *
	 * @param remoteObjectName the remote object that correlates to the closed
	 *                         listenert
	 * @return the list of removed closed listeners
	 */
	protected List<ClosedListener> removeClosedListenerList(String remoteObjectName) {
		return remoteObjectClosedListenersList.remove(remoteObjectName);
	}

	/**
	 * Method used by the Lookup-Classes to register a closed listener with a
	 * given remote object name
	 *
	 * @param listener         the listener to add
	 * @param remoteObjectName the obejct that we listen for closed situations
	 */
	protected void addClosedListener(ClosedListener listener, String remoteObjectName) {
		if (!remoteObjectClosedListenersList.containsKey(remoteObjectName)) {
			List<ClosedListener> closedListeners = Collections.synchronizedList(new ArrayList<>());
			closedListeners.add(listener);
			remoteObjectClosedListenersList.put(remoteObjectName, closedListeners);
		} else {
			remoteObjectClosedListenersList.get(remoteObjectName).add(listener);
		}
	}

	/**
	 * Method used by the lookup-Classes to remove a single closed listener from
	 * a remote object
	 *
	 * @param listener         the listener to remove
	 * @param remoteObjectName the related remote object
	 * @return true if listener has been removed
	 */
	protected boolean removeClosedListener(ClosedListener listener, String remoteObjectName) {
		if (remoteObjectClosedListenersList.containsKey(remoteObjectName)) {

			// remove the listener from the list
			boolean result = remoteObjectClosedListenersList.get(remoteObjectName).remove(listener);

			// if the list is now empty, remove the liste from the map
			if (remoteObjectClosedListenersList.get(remoteObjectName).isEmpty()) {
				remoteObjectClosedListenersList.remove(remoteObjectName);
			}
			// return the result of the removal
			return result;
		} else {
			return false;
		}
	}

	/**
	 * Creates a packet dispatcher which delegates the packet-reading to
	 * {@link ProcessMessageRunnable}'s which run in the given
	 * <code>threadPool</code>
	 *
	 * @param serverString an identifier string to determine to which server
	 *                     this dispatcher is connected to. this must be set to <code>null</code> if
	 *                     this dispatcher is a server dispatcher.
	 * @param threadPool   the pool where the {@link ProcessMessageRunnable}'s run
	 *                     in
	 */
	public Dispatcher(String serverString, /* See: http://dev.root1.de/issues/127 */ ClassLoader classLoader, ExecutorService threadPool) {
		log.debug("begin");

		isRunning = true;

		this.simonRefQueue = new SimonRefQueue<>(this);

		this.serverString = serverString;
		this.lookupTable = new LookupTable(this);

		this.messageProcessorPool = threadPool;

		this.pingWatchdog = new PingWatchdog(this);

		this.classLoader = classLoader;

		log.debug("end");
	}

	/**
	 * Sends a remote object lookup to the server
	 *
	 * @param session          the related session over which the invoke request comes
	 * @param remoteObjectName the remote object to lookup
	 * @return the name lookup return message
	 * @throws LookupFailedException if lookup fails
	 * @throws SimonRemoteException  if something else goes wrong
	 */
	protected MsgNameLookupReturn invokeNameLookup(IoSession session, String remoteObjectName) throws LookupFailedException, SimonRemoteException {
		checkForInvalidState(session, "Simon.lookup({...}, " + remoteObjectName + ")");
		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgNameLookup msgNameLookup = new MsgNameLookup();
		msgNameLookup.setSequence(sequenceId);
		msgNameLookup.setRemoteObjectName(remoteObjectName);

		session.write(msgNameLookup);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);
		MsgNameLookupReturn result = (MsgNameLookupReturn) getRequestResult(sequenceId);

		log.debug("got answer for sequenceId={}", sequenceId);
		log.trace("end sequenceId={}", sequenceId);

		return result;
	}

	/**
	 * Sends a remote object lookup to the server
	 *
	 * @param session                the related session over which the invoke request comes
	 * @param canonicalInterfaceName the canonical name of the interface
	 * @return the interface lookup return message
	 * @throws LookupFailedException if lookup fails
	 * @throws SimonRemoteException
	 */
	protected MsgInterfaceLookupReturn invokeInterfaceLookup(IoSession session, String canonicalInterfaceName) throws LookupFailedException, SimonRemoteException {
		checkForInvalidState(session, "Simon.lookup({...}, " + canonicalInterfaceName + ")");
		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgInterfaceLookup msgInterfaceLookup = new MsgInterfaceLookup();
		msgInterfaceLookup.setSequence(sequenceId);
		msgInterfaceLookup.setCanonicalInterfaceName(canonicalInterfaceName);

		session.write(msgInterfaceLookup);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);
		MsgInterfaceLookupReturn result = (MsgInterfaceLookupReturn) getRequestResult(sequenceId);

		log.debug("got answer for sequenceId={}", sequenceId);
		log.trace("end sequenceId={}", sequenceId);

		return result;
	}

	private Object[] prepareArgumentsForSend(Object[] args, IoSession session) {
		if (args == null) {
			return null;
		}
		Object[] preparedArgs = args.clone();

		for (int i = 0; i < preparedArgs.length; i++) {
			Object arg = preparedArgs[i];
			if (arg == null) continue;

			if (Utils.isSimonProxy(arg)) {
				log.debug("Argument {} is a SimonProxy. Replacing with SimonRemoteInstance.", i);
				preparedArgs[i] = new SimonRemoteInstance(session, arg);
				log.debug("Replaced arg {} with: {}", i, preparedArgs[i]);
			}
			else if (Utils.isValidRemote(arg)) {
				log.debug("Argument {} is a local remote-capable object. Replacing with SimonRemoteInstance.", i);
				SimonRemoteInstance sri = new SimonRemoteInstance(session, arg);
				preparedArgs[i] = sri;
				lookupTable.putRemoteInstance(session.getId(), sri, arg);
				log.debug("Replaced arg {} with: {}. Registered locally.", i, preparedArgs[i]);
			}
			else if (arg instanceof SimonEndpointReference) {
				log.debug("Argument {} is SimonEndpointReference, passing as is.", i);
			}
			else if (!(arg instanceof Serializable)) {
				log.warn("Argument {} ({}) is not Serializable and not a remote object/proxy. Serialization might fail.", i, arg.getClass().getName());
			}
		}
		return preparedArgs;
	}

	protected void sendAsyncInvoke(IoSession session, String remoteObjectName, Method method, Object[] args) throws SimonRemoteException {
		checkForInvalidState(session, method.toString() + " (async)");
		final int sequenceId = generateSequenceId();
		log.debug("begin async send sequenceId={} session={}", sequenceId, session);

		Object[] preparedArgs = prepareArgumentsForSend(args, session);

		MsgInvoke msgInvoke = new MsgInvoke();
		msgInvoke.setSequence(sequenceId);
		msgInvoke.setRemoteObjectName(remoteObjectName);
		msgInvoke.setMethod(method);
		msgInvoke.setArguments(preparedArgs);

		session.write(msgInvoke);

		log.debug("end async send sequenceId={}", sequenceId);
	}

	/*

	 * Sends a method invocation request to the remote host.
	 *
	 * @param session the related session over which the invoke request comes
	 * @param remoteObjectName the remote object
	 * @param method the method ti invoke on the remote
	 * @param args the arguments for the method
	 * @return the result of the invoked method
	 * @throws SimonRemoteException
	 */
	protected Object invokeMethod(IoSession session, String remoteObjectName, Method method, Object[] args) throws SimonRemoteException {

		checkForInvalidState(session, method.toString());

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		Object[] preparedArgs = prepareArgumentsForSend(args, session);

		MsgInvoke msgInvoke = new MsgInvoke();
		msgInvoke.setSequence(sequenceId);
		msgInvoke.setRemoteObjectName(remoteObjectName);
		msgInvoke.setMethod(method);
		msgInvoke.setArguments(preparedArgs);

		session.write(msgInvoke);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);
		int customWaitTimeout = Simon.getCustomInvokeTimeout(method);

		if (customWaitTimeout > 0) {
			waitForResult(session, monitor, customWaitTimeout);
		} else {
			waitForResult(session, monitor);
		}

		MsgInvokeReturn result = (MsgInvokeReturn) getRequestResult(sequenceId);

		log.debug("end sequenceId={}", sequenceId);
		return result.getReturnValue();
	}

	/**
	 * Sends a "toString()" request to the remote host.
	 *
	 * @param session          the related session over which the invoke request comes
	 * @param remoteObjectName the remote object
	 * @return the result of the remote "toString()" call
	 * @throws SimonRemoteException
	 */
	protected String invokeToString(IoSession session, String remoteObjectName) throws SimonRemoteException {
		checkForInvalidState(session, "toString()");

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgToString msgInvoke = new MsgToString();
		msgInvoke.setSequence(sequenceId);
		msgInvoke.setRemoteObjectName(remoteObjectName);

		session.write(msgInvoke);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);
		MsgToStringReturn result = (MsgToStringReturn) getRequestResult(sequenceId);

		if (result.hasError()) {
			throw new SimonRemoteException(result.getErrorMsg());
		}

		log.debug("got answer for sequenceId={}", sequenceId);
		log.debug("end sequenceId={}", sequenceId);

		return result.getReturnValue();
	}

	/**
	 * Invokes the hashCode() method on the remote object
	 *
	 * @param session          the related session over which the invoke request comes
	 * @param remoteObjectName the remote object
	 * @return the result of the remote "hashCode()" call
	 * @throws SimonRemoteException if something goes wrong
	 */
	protected int invokeHashCode(IoSession session, String remoteObjectName) throws SimonRemoteException {

		checkForInvalidState(session, "hashCode()");

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgHashCode msgInvoke = new MsgHashCode();
		msgInvoke.setSequence(sequenceId);
		msgInvoke.setRemoteObjectName(remoteObjectName);

		session.write(msgInvoke);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);
		MsgHashCodeReturn result = (MsgHashCodeReturn) getRequestResult(sequenceId);

		if (result.hasError()) {
			throw new SimonRemoteException(result.getErrorMsg());
		}

		log.debug("got answer for sequenceId={}", sequenceId);
		log.debug("end sequenceId={}", sequenceId);

		return result.getReturnValue();
	}

	/**
	 * Forwards an "equals()" call to the remote side to be handled there
	 *
	 * @param session             the session to which the invocation is sent
	 * @param remoteObjectName    the name of the remote object that has to be
	 *                            compared
	 * @param objectToCompareWith the object to which the remote object is
	 *                            compared with
	 * @return the result of the comparison
	 * @throws SimonRemoteException if something goes wrong
	 */
	protected boolean invokeEquals(IoSession session, String remoteObjectName, Object objectToCompareWith) throws SimonRemoteException {
		checkForInvalidState(session, "equals()");

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgEquals msgEquals = new MsgEquals();
		msgEquals.setSequence(sequenceId);
		msgEquals.setRemoteObjectName(remoteObjectName);
		msgEquals.setObjectToCompareWith(objectToCompareWith);

		session.write(msgEquals);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);
		MsgEqualsReturn result = (MsgEqualsReturn) getRequestResult(sequenceId);

		if (result.hasError()) {
			throw new SimonRemoteException(result.getErrorMsg());
		}

		log.debug("got answer for sequenceId={}", +sequenceId);
		log.debug("end sequenceId={}", sequenceId);

		return result.getEqualsResult();
	}

	/**
	 * Waits at most one hour for the result of request described by the
	 * monitor. If result is not present within this time, a
	 * SimonRemoteException will be placed as the result. This is to ensure that
	 * the client cannot wait forever for a result.
	 *
	 * @param session the session on which the request was placed
	 * @param monitor the monitor related to the request
	 */
	private void waitForResult(IoSession session, final SequenceMonitor monitor) {
		// wait at most 10 sec
		waitForResult(session, monitor, 10000);
	}

	/**
	 * Waits at most <code>timeout</code> ms for the result of request described by the
	 * monitor. If result is not present within this time, a
	 * SimonRemoteException will be placed as the result. This is to ensure that
	 * the client cannot wait forever for a result.
	 *
	 * @param session the session on which the request was placed
	 * @param monitor the monitor related to the request
	 * @param timeout timeout for waiting for result
	 */
	private void waitForResult(IoSession session, final SequenceMonitor monitor, int timeout) {
		int sequenceId = monitor.getSequenceId();
		int counter = 0;

		// wait for result
		long startWaiting = System.currentTimeMillis();

		// just make sure that the while-loop cannot wait forever for the result
		long waitLoopCount = timeout / Statics.MONITOR_WAIT_TIMEOUT;

		while (!isRequestResultPresent(sequenceId)) {

			if (counter++ == waitLoopCount) {
				putResultToQueue(session, sequenceId, new InvokeTimeoutException("Waited too long for invocation result."));
			}

			if (monitor.waitForSignal(Statics.MONITOR_WAIT_TIMEOUT)) {
				return;
			}

			if (log.isTraceEnabled()) { // used IF to avoid calculating if no trace enabled
				log.trace("still waiting for result for sequenceId={}. Waiting since {}ms.", sequenceId, (
						System.currentTimeMillis() - startWaiting));
			}
		}
	}

	/**
	 * This method is called from worker-threads which processed an invocation
	 * and have data ready that has to be returned to the "caller". after adding
	 * the result to the map (means: replacing the monitor with the result), the
	 * waiting request-method is waked.
	 *
	 * @param session    the underlying session
	 * @param sequenceId the sequence id that is waiting for the result
	 * @param o          the result itself
	 */
	public void putResultToQueue(final IoSession session, final int sequenceId, final Object o) {
		log.debug("begin");

		log.debug("sequenceId={} msg={}", sequenceId, o);

		boolean isResultForOutstandingRequest = false;

		// remove the sequenceId from the map/list of open requests
		synchronized (sessionHasRequestPlaced) {

			assert sessionHasRequestPlaced.containsKey(session);

			// check if there is already a list with requests for this session
			List<Integer> requestListForSession = sessionHasRequestPlaced.get(session);

			if (requestListForSession != null) {

				synchronized (requestListForSession) {

					if (requestListForSession.contains(sequenceId)) {
						requestListForSession.remove((Integer) sequenceId);

						// if there is no more request open, remove the session from the list
						if (requestListForSession.isEmpty()) {
							sessionHasRequestPlaced.remove(session);
						}
						isResultForOutstandingRequest = true;
					} else {
						log.debug("Session {} and sequenceId {} do currently not wait for a result.", Utils.longToHexString(session.getId()), sequenceId);
					}
				}
			} else {
				log.warn("Session {} has no outstanding requests. Result no longer awaited?", Utils.longToHexString(session.getId()));
			}
		}
		if (isResultForOutstandingRequest) {
			// retrieve monitor
			SequenceMonitor monitor = (SequenceMonitor) requestMonitorAndResultMap.get(sequenceId);
			// replace monitor with result
			requestMonitorAndResultMap.put(sequenceId, o);
			monitor.signal();
		} else {
			log.warn("Result '{}' for session {} and sequenceId {} dropped.", new Object[]{
					o,
					Utils.longToHexString(session.getId()),
					sequenceId
			});
		}
		log.debug("end");
	}

	/**
	 * for internal use only
	 *
	 * @return the associated lookup table
	 */
	protected LookupTable getLookupTable() {
		return lookupTable;
	}

	/**
	 * Initiates a shutdown at the dispatcher and all related things
	 */
	public void shutdown() {
		log.debug("begin");

		// need to be done before shutdown-status is set
		simonRefQueue.cleanup();

		shutdownInProgress = true;
		messageProcessorPool.shutdown();

		while (!messageProcessorPool.isShutdown()) {
			log.debug("waiting for messageProcessorPool to shutdown...");
			try {
				Thread.sleep(Statics.WAIT_FOR_SHUTDOWN_SLEEPTIME);
			} catch (InterruptedException e) {
				// nothing to do
			}
		}

		lookupTable.cleanup();
		isRunning = false;
		log.debug("shutdown completed");
		log.debug("end");
	}

	/**
	 * Returns the identifier string which determines to which server this
	 * dispatcher is connected to
	 *
	 * @return the identifier string. this is <code>null</code> if this
	 * dispatcher is a server dispatcher
	 */
	public String getServerString() {
		return serverString;
	}

	/**
	 * Returns whether this is an server dispatcher or not
	 *
	 * @return true if server dispatcher, false if not
	 */
	protected boolean isServerDispatcher() {
		return (serverString == null);
	}

	/**
	 * Returns whether the dispatcher is still in run() or not
	 *
	 * @return boolean
	 */
	protected boolean isRunning() {
		return isRunning;
	}

	/**
	 * Internal method for checking for invalid state before executing
	 * invoke-actions. If the state is okay, the method will simply return
	 * without any notice.
	 *
	 * @param method the method name that we will try to call afterwards...
	 *               Could be left to \"\", but it's useful for logging purpose
	 * @throws SessionException occurs when the system is already in shutdown
	 *                          process and no further remote call can be made
	 */
	private void checkForInvalidState(IoSession session, String method) throws SessionException {
		if (shutdownInProgress && isRunning) {
			throw new SessionException("Cannot handle method call \"" + method + "\" while shutdown.");
		}
		if (!isRunning) {
			throw new SessionException(
					"Cannot handle method call \"" + method + "\" on already closed Dispatcher for Session " +
							Utils.longToHexString(session.getId()) + ".");
		} else if (session.isClosing()) {
			throw new SessionException("Cannot handle method call \"" + method + "\" on already closed Session(" +
					Utils.longToHexString(session.getId()) + ").");
		}
	}

	/**
	 * create a monitor that waits for the request-result that is associated
	 * with the given request-id
	 *
	 * @param sequenceId
	 * @return the monitor used for waiting for the result
	 */
	private SequenceMonitor createMonitor(final IoSession session, final int sequenceId) {
		log.debug("begin");

		final SequenceMonitor monitor = new SequenceMonitor(sequenceId);

		synchronized (sessionHasRequestPlaced) {
			// check if there is already a list with requests for this session
			if (!sessionHasRequestPlaced.containsKey(session)) {
				List<Integer> requestListForSession = new ArrayList<>();
				requestListForSession.add(sequenceId);
				sessionHasRequestPlaced.put(session, requestListForSession);
			} else {
				// .. otherwise, get the list and add the sequenceId
				sessionHasRequestPlaced.get(session).add(sequenceId);
			}
		}

		// put the monitor in the result-map
		requestMonitorAndResultMap.put(sequenceId, monitor);

		log.debug("created monitor for sequenceId={}", sequenceId);

		log.debug("end");
		return monitor;
	}

	/**
	 * Returns the result of the already placed request. Make sure that you
	 * where notified about the received result. Checks if the request result is
	 * an {@link SimonRemoteException}. If yes, the exception is thrown, if not,
	 * the result is returned
	 *
	 * @param sequenceId the sequence-id related to the result
	 * @return the result of the request. May be null if there is no result yet.
	 * @throws SimonRemoteException
	 */
	private Object getRequestResult(final int sequenceId) throws SimonRemoteException {
		log.debug("getting result for sequenceId={}", sequenceId);

		Object o = requestMonitorAndResultMap.remove(sequenceId);

		if (o instanceof SimonRemoteException) {
			log.debug("result is an exception, throwing it ...");
			throw ((SimonRemoteException) o);
		}
		return o;
	}

	/**
	 * Returns whether the given sequenceId already has a result present or not
	 *
	 * @param sequenceId the sequence-id related to the result
	 * @return true, if the result is present, false if not
	 */
	private boolean isRequestResultPresent(final int sequenceId) {
		boolean present = false;
		// if the contained object is NOT an instance of Monitor, present=true
		log.trace("result={}", requestMonitorAndResultMap.get(sequenceId));
		if (!(requestMonitorAndResultMap.get(sequenceId) instanceof SequenceMonitor)) {
			present = true;
		}
		log.debug("Result for sequenceId={} present: {}", sequenceId, present);
		return present;
	}

	/**
	 * Generates a sequence ID<br> IDs have a unique value from
	 * 0..Integer.MAX_VALUE<br> The range should be big enough so that there
	 * should not be two oder more identical IDs
	 *
	 * @return a request ID
	 */
	private synchronized Integer generateSequenceId() {
		// if maximum reached, get maximum and set back to zero. otherwise just return the incremented value
		return (sequenceIdCounter.incrementAndGet() ==
				Integer.MAX_VALUE ? sequenceIdCounter.getAndSet(0) : sequenceIdCounter.intValue());
	}

	/**
	 * Interrupts all waiting requests for given session. Returned value would
	 * be a SimonRemoteException
	 *
	 * @param session the session which requests to be interrupted
	 */
	private void interruptWaitingRequests(IoSession session) {
		List<Integer> remove = sessionHasRequestPlaced.get(session);
		// if there is a list with objects to remove
		if (remove != null) {
			List<Integer> removeCopy = new ArrayList<Integer>(remove);
			for (Integer sequenceId : removeCopy) {
				putResultToQueue(session, sequenceId, new SimonRemoteException(
						"session was closed. sessionId=" + Utils.longToHexString(session.getId()) + " sequenceId=" +
								sequenceId));
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandler#exceptionCaught(org.apache.mina.core.session.IoSession, java.lang.Throwable)
	 */
	@Override
	public void exceptionCaught(IoSession session, Throwable throwable) throws Exception {

		log.error("exception Caught. thread={} session={}. Exception:\n {}", Thread.currentThread().getName(), Utils.longToHexString(session.getId()), Utils.getStackTraceAsString(throwable));

		log.debug("Closing the session now! session={}", Utils.longToHexString(session.getId()));
		session.closeNow();
	}

	/*
	 * (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandler#messageReceived(org.apache.mina.core.session.IoSession, java.lang.Object)
	 */
	@Override
	public void messageReceived(IoSession session, Object message) throws Exception {
		log.debug("Received message from session {}", Utils.longToHexString(session.getId()));
		AbstractMessage abstractMessage = (AbstractMessage) message;
		messageProcessorPool.execute(new ProcessMessageRunnable(this, session, abstractMessage));
	}

	/*
	 * (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandler#messageSent(org.apache.mina.core.session.IoSession, java.lang.Object)
	 */
	@Override
	public void messageSent(IoSession session, Object msg) throws Exception {
		log.debug("Message sent to session session={} msg='{}'", Utils.longToHexString(session.getId()), msg);
	}

	/*
	 * (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandler#sessionClosed(org.apache.mina.core.session.IoSession)
	 */
	@Override
	public void sessionClosed(IoSession session) throws Exception {
		String id = Utils.longToHexString(session.getId());

		log.debug("{} ################################################", id);
		log.debug("{} ######## session closed", id);

		final SimonSessionListener listener = sessionListenerRef.get();
		if (listener != null) {
			try {
				log.debug("Notifying SimonSessionListener about session closure: {}", session.getId());
				listener.simonSessionClosed(session, this);
			} catch (Exception e) {
				log.error("Error in SimonSessionListener during sessionClosed for session {}", id, e);
			}
		} else {
			log.trace("No SimonSessionListener registered for session closed event: {}", id);
		}

		lookupTable.unreference(session.getId());
		interruptWaitingRequests(session);

		// remove attached references
		log.debug("{} ######## Removing session attributes ...", id);
		log.debug("{} ########  -> {}", id, Statics.SESSION_ATTRIBUTE_DISPATCHER);
		session.removeAttribute(Statics.SESSION_ATTRIBUTE_DISPATCHER);
		log.debug("{} ########  -> {}", id, Statics.SESSION_ATTRIBUTE_LOOKUPTABLE);
		session.removeAttribute(Statics.SESSION_ATTRIBUTE_LOOKUPTABLE);

		log.debug("{} ######## notify closed listeners", id);
		// notify all still attached closed listeners that the one and only session to the server is closed.
		for (String ron : remoteObjectClosedListenersList.keySet()) {
			List<ClosedListener> list = remoteObjectClosedListenersList.remove(ron);
			for (ClosedListener closedListener : list) {
				closedListener.closed();
			}
			list.clear();
		}

		// see issue #130
		if (!isReleased()) {
			log.debug("{} ######## Releasing dispatcher {}", id, this);
			AbstractLookup.releaseDispatcher(this);
		} else {
			log.debug("{} ######## Dispatcher {} already released. Nothing to do.", id, this);
		}

		log.debug("{} ######## Session close *DONE*", id);
		log.debug("{} ################################################", id);
	}

	/**
	 * Indicates whether someone has set this dispatcher as "released". This
	 * should only happen at one place:
	 * {@link AbstractLookup#releaseDispatcher(Dispatcher)}.
	 *
	 * @return true if released, false of not
	 */
	boolean isReleased() {
		return released;
	}

	/**
	 * Method used by AbstractLookup when releasing dispatcher.
	 * {@link AbstractLookup#releaseDispatcher(Dispatcher)} is
	 * shutting down the dispatcher + closing the session. After the session has
	 * been closed, {@link Dispatcher#sessionClosed(org.apache.mina.core.session.IoSession)
	 * } will be triggered. As the release has already been done or is in
	 * progress, we don't need to release again. So, release in "sessionClosed"
	 * should only happen when someone else (crash, error, ...) closes the
	 * session. That's for what this flag is good for.
	 */
	void setReleased() {
		this.released = true;
	}

	/*
	 * (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandler#sessionCreated(org.apache.mina.core.session.IoSession)
	 */
	@Override
	public void sessionCreated(IoSession session) throws Exception {
		log.debug("session created. session={}", session);
		session.setAttribute(Statics.SESSION_ATTRIBUTE_LOOKUPTABLE, lookupTable); // attach the lookup table to the session
		session.setAttribute(Statics.SESSION_ATTRIBUTE_DISPATCHER, this); // attach a reference to the dispatcher.

		final SimonSessionListener listener = sessionListenerRef.get();
		if (listener != null) {
			listener.simonSessionCreated(session, this);
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandler#sessionIdle(org.apache.mina.core.session.IoSession, org.apache.mina.core.session.IdleStatus)
	 */
	@Override
	public void sessionIdle(IoSession session, IdleStatus idleStatus) throws Exception {
		log.debug("session idle. session={} idleStatus={}", Utils.longToHexString(session.getId()), idleStatus);

		if (!session.isClosing()) {
			if (idleStatus == IdleStatus.READER_IDLE || idleStatus == IdleStatus.BOTH_IDLE) {
				log.trace("sending ping to test session {}", Utils.longToHexString(session.getId()));
				sendPing(session);
			}
		}
	}

	private void sendPing(IoSession session) throws SessionException {
		checkForInvalidState(session, "sendPing()");
		pingWatchdog.waitForPong(session);

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		MsgPing msgPing = new MsgPing();
		msgPing.setSequence(sequenceId);

		session.write(msgPing);

		log.debug("end. data send.");
	}

	public void sendPong(IoSession session) throws SessionException {
		checkForInvalidState(session, "sendPong()");

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		MsgPong msgPong = new MsgPong();
		msgPong.setSequence(sequenceId);

		session.write(msgPong);

		log.debug("end. data send.");
	}

	/*
	 * (non-Javadoc)
	 * @see org.apache.mina.core.service.IoHandler#sessionOpened(org.apache.mina.core.session.IoSession)
	 */
	@Override
	public void sessionOpened(IoSession session) throws Exception {
		log.debug("session opened. session={}", session);
		SimonSessionListener listener = sessionListenerRef.get();
		if (listener != null) {
			listener.simonSessionOpened(session, this);
		}
	}

	/**
	 * Opens the a raw channel on the given session with the specified token
	 *
	 * @return the opened RawChannel
	 * @throws SimonRemoteException if a problem occured while opening the raw
	 *                              channel
	 */
	protected RawChannel openRawChannel(IoSession session, int channelToken) throws SimonRemoteException {
		checkForInvalidState(session, "openRawChannel()");

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={} token={}", new Object[]{
				sequenceId,
				session,
				channelToken
		});

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgOpenRawChannel msgOpenRawChannel = new MsgOpenRawChannel();
		msgOpenRawChannel.setSequence(sequenceId);
		msgOpenRawChannel.setChannelToken(channelToken);

		session.write(msgOpenRawChannel);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);
		MsgOpenRawChannelReturn result = (MsgOpenRawChannelReturn) getRequestResult(sequenceId);

		log.debug("got answer for sequenceId={}", sequenceId);
		log.debug("end sequenceId={}", sequenceId);

		if (result.hasError()) {
			throw new SimonRemoteException(result.getErrorMsg());
		}

		if (result.getReturnValue()) {
			log.debug("Creating RawChannel object with token={}", channelToken);
			return new RawChannel(this, session, channelToken);
		}

		throw new SimonRemoteException("channel could not be opened. Maybe token was wrong?!");
	}

	/**
	 * TODO document me
	 *
	 * @throws SimonException
	 */
	protected int prepareRawChannel(RawChannelDataListener listener) throws SimonException {
		int channelToken = getRawChannelToken();
		synchronized (rawChannelMap) {
			rawChannelMap.put(channelToken, listener);
		}
		log.trace("rawChannelMap={}", rawChannelMap);
		return channelToken;
	}

	/**
	 * TODO document me
	 */
	protected boolean isRawChannelDataListenerRegistered(int channelToken) {
		log.trace("searching in map for token={} map={}", channelToken, rawChannelMap);
		synchronized (rawChannelMap) {
			return rawChannelMap.containsKey(channelToken);
		}
	}

	/**
	 * TODO document me
	 */
	protected RawChannelDataListener getRawChannelDataListener(int channelToken) {
		log.trace("getting listener token={} map={}", channelToken, rawChannelMap);
		synchronized (rawChannelMap) {
			return rawChannelMap.get(channelToken);
		}
	}

	/**
	 * TODO document me
	 *
	 * @throws SimonException
	 */
	private int getRawChannelToken() throws SimonException {
		synchronized (tokenList) {

			for (int i = Integer.MIN_VALUE; i < Integer.MAX_VALUE; i++) {
				if (!tokenList.contains(i)) {
					tokenList.add(i);
					return i;
				}
			}
		}
		throw new SimonException("no more token available");
	}

	/**
	 * TODO document me
	 */
	private void releaseToken(int channelToken) {
		synchronized (tokenList) {
			// if not cast to integer object, the entry at
			// index "channelToken" is removed,
			// instead of the channelToken itself.
			// means: wrong remove() method would be used
			tokenList.remove((Integer) channelToken);
		}
	}

	/**
	 * TODO document me
	 *
	 * @param channelToken
	 */
	protected void unprepareRawChannel(int channelToken) throws RawChannelException {
		log.debug("token={}", channelToken);
		releaseToken(channelToken);
		synchronized (rawChannelMap) {
			RawChannelDataListener rawChannelDataListener = rawChannelMap.remove(channelToken);
			rawChannelDataListener.close();
		}
	}

	/**
	 * TODO document me
	 *
	 * @param session
	 * @param channelToken
	 * @param byteBuffer
	 * @throws SimonRemoteException
	 */
	protected void writeRawData(IoSession session, int channelToken, ByteBuffer byteBuffer) throws SimonRemoteException, RawChannelException {
		checkForInvalidState(session, "writeRawData()");

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={}", sequenceId, session);

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgRawChannelData msgRawChannelData = new MsgRawChannelData();
		msgRawChannelData.setSequence(sequenceId);
		msgRawChannelData.setChannelToken(channelToken);
		msgRawChannelData.setData(byteBuffer);

		session.write(msgRawChannelData);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);

		//retrieve the return msg to remove the monitor etc.
		MsgRawChannelDataReturn requestResult = (MsgRawChannelDataReturn) getRequestResult(sequenceId);

		if (requestResult.hasError()) {
			throw new RawChannelException(requestResult.getErrorMsg());
		}

		log.debug("end. got ack for data send for sequenceId={} and channelToken={}", sequenceId, channelToken);
	}

	/**
	 * Triggers a close of a raw channel
	 *
	 * @param session      the related IoSession
	 * @param channelToken the related channel token
	 */
	protected void closeRawChannel(IoSession session, int channelToken) throws SimonRemoteException, RawChannelException {
		checkForInvalidState(session, "closeRawChannel()");

		final int sequenceId = generateSequenceId();

		log.debug("begin sequenceId={} session={} token={}", new Object[]{
				sequenceId,
				session,
				channelToken
		});

		// create a monitor that waits for the request-result
		final SequenceMonitor monitor = createMonitor(session, sequenceId);

		MsgCloseRawChannel msgCloseRawChannel = new MsgCloseRawChannel();
		msgCloseRawChannel.setSequence(sequenceId);
		msgCloseRawChannel.setChannelToken(channelToken);

		session.write(msgCloseRawChannel);

		log.debug("data send. waiting for answer for sequenceId={}", sequenceId);

		waitForResult(session, monitor);
		MsgCloseRawChannelReturn result = (MsgCloseRawChannelReturn) getRequestResult(sequenceId);

		log.debug("got answer for sequenceId={}", sequenceId);
		log.debug("end sequenceId={}", sequenceId);

		if (result.hasError()) {
			throw new RawChannelException(result.getErrorMsg());
		}

		if (result.getReturnValue()) {
			return;
		}

		throw new SimonRemoteException("channel could not be closed. Maybe token was wrong?!");
	}

	/**
	 * Returns the PingWatchdog that checks the session connectivity
	 *
	 * @return the current instance of PingWatchdog
	 */
	protected PingWatchdog getPingWatchdog() {
		return pingWatchdog;
	}

	/**
	 * Returns a list of ClosedListeners which listen for closed event for given
	 * remote object name
	 *
	 * @param remoteObjectName the remote objects name
	 * @return the list with listeners
	 */
	protected List<ClosedListener> getClosedListenerList(String remoteObjectName) {
		return remoteObjectClosedListenersList.get(remoteObjectName);
	}

	/**
	 * Classloader used to load remote interface classes etc.
	 *
	 * @return ClassLoader
	 * @since 1.2.0
	 */
	ClassLoader getClassLoader() {
		return classLoader;
	}

	void sendReleaseRef(IoSession session, String refId) throws SessionException {
		checkForInvalidState(session, "sendReleaseRef()");
		log.debug("#######################");
		log.debug("########### ReleaseRef for {}", refId);
		log.debug("#######################");

		log.debug("begin session={} refId={}", session, refId);

		MsgReleaseRef msgReleaseRef = new MsgReleaseRef();
		msgReleaseRef.setRefId(refId);
		// we don't care about seq id in this case, but we need it for the protocol
		msgReleaseRef.setSequence(generateSequenceId());

		session.write(msgReleaseRef);

		log.debug("end. data send.");
	}

	/**
	 * TODO document me
	 *
	 * @return
	 */
	SimonRefQueue<SimonPhantomRef<?>> getRefQueue() {
		return simonRefQueue;
	}

	// See: https://issues.apache.org/jira/browse/DIRMINA-785
	@Override
	public void inputClosed(IoSession is) throws Exception {
		is.closeNow();
	}

	@Override
	public void event(IoSession session, FilterEvent event) throws Exception {
	}
}
