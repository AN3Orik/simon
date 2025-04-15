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

import host.anzo.simon.exceptions.LookupFailedException;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.session.IoSession;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.*;

/**
 * This class is "the brain" of SIMON. It saves all known remote object - name
 * relations, as well as hashcodes for all the methods in the remote object. If
 * a object is getting unreferenced over the network connection, it gets
 * "informed" by the
 * <code>unreferenced()</code> method, if {@link SimonUnreferenced} is
 * implemented.
 *
 * @author ACHR
 */
@Slf4j
public class LookupTable implements LookupTableMBean {
	/**
	 * Maps the remote object name to the remote object. Only Objects wich have
	 * been registered with the Registry.bind() method are added to this map.
	 */
	private final HashMap<String, RemoteObjectContainer> bindings = new HashMap<>();

	/**
	 * A Map that holds a list of remote object names for each socket connection.
	 * The names are used to clean up upon DGC / session close
	 *
	 * <pre>
	 * &lt;Session-ID, List&lt;remoteObjectName&gt;&gt;
	 * </pre>
	 */
	private final Map<Long, List<String>> gcRemoteInstances = new HashMap<>();

	/**
	 * Maps the remote object to the map with the hash-mapped methods.
	 */
	private final Map<Object, Map<Long, Method>> remoteObject_to_hashToMethod_Map = new HashMap<>();

	/**
	 * Set with remote object instances. Used to identify already registered
	 * remote objects.
	 * The same information is also available in "bindings", but query would be complexer/more time consuming.
	 *
	 * @since 1.2.0
	 */
	private final Set<Object> remoteobjectSet = new HashSet<>();

	/**
	 * Container for callback references
	 * <pre>
	 * &lt;sessionId, &lt;refId, RemoteRefContainer&gt;&gt;
	 * </pre>
	 */
	private final Map<Long, Map<String, RemoteRefContainer>> sessionRefCount = new HashMap<>();


	private Dispatcher dispatcher;
	private boolean cleanupDone = false;

	/**
	 * Called via Dispatcher to create a lookup table. There's only one
	 * LookupTable for one Dispatcher.
	 *
	 * @param dispatcher
	 */
	protected LookupTable(Dispatcher dispatcher) {
		this.dispatcher = dispatcher;
		Simon.registerLookupTable(this);

		String objectNameOfMBean = "host.anzo.simon:" + "type=" + MBEAN_TYPE + "," + "subType=" +
				(dispatcher.getServerString() ==
						null ? MBEAN_SUBTYPE_SERVER : MBEAN_SUBTYPE_CLIENT) + "," + "instance=" +
				MBEAN_TYPE + "@" + hashCode();
		Utils.registerMBean(this, objectNameOfMBean);
	}

	/**
	 * Saves a remote object in the lookup table for later reference
	 *
	 * @param remoteObjectName the name of the remote object
	 * @param remoteObject     a simon remote object
	 */
	synchronized void putRemoteBinding(String remoteObjectName, Object remoteObject) {
		log.debug("begin");

		log.debug("remoteObjectName={} object={}", remoteObjectName, remoteObject);

		addRemoteObjectToSet(remoteObject);

		RemoteObjectContainer roc = new RemoteObjectContainer(remoteObject, remoteObjectName, remoteObject.getClass().getInterfaces());
		bindings.put(remoteObjectName, roc);

		log.debug("Put {} to remoteObject_to_hashToMethod_Map", remoteObject);
		Map<Long, Method> put = remoteObject_to_hashToMethod_Map.put(remoteObject, computeMethodHashMap(remoteObject.getClass()));
		if (put != null) {
			log.error("remoteobject {} already existed int remoteObject_to_hashToMethod_Map", remoteObject);
		}
		log.debug("end");
	}

	/**
	 * Stores remote objects. Normally it wouldn't be required to store the
	 * remote objects in a separate set/map, but bindings contains
	 * (remoteobjectname/RemoteObjectContainer) pairs, which makes search by
	 * object complexer/slower.
	 *
	 * @param remoteObject
	 */
	private void addRemoteObjectToSet(Object remoteObject) {
		int hashCode = remoteObject.hashCode();
		remoteobjectSet.add(remoteObject);
		log.trace("Adding remote object {} with hash={}", remoteObject, hashCode);
	}


	/**
	 * Adds a callback reference to the internal reference storage
	 *
	 * @param sessionId the related session id
	 * @param refId     the reference if for the object to reference
	 * @param object    the object to reference
	 */
	private void addCallbackRef(long sessionId, String refId, Object object) {

		log.debug("Adding {}", refId);
		synchronized (sessionRefCount) {
			Map<String, RemoteRefContainer> sessionMap = sessionRefCount.get(sessionId);

			if (sessionMap == null) {
				// session not yet known, creating new ref container
				sessionMap = new HashMap<>();
				sessionMap.put(refId, new RemoteRefContainer(object));
				log.debug("Added RefCounter for {}. {}", refId, toString());
				sessionRefCount.put(sessionId, sessionMap);
			} else {
				RemoteRefContainer ref = sessionMap.get(refId);
				if (ref == null) {
					// session known, but no ref container yet
					ref = new RemoteRefContainer(object);
					sessionMap.put(refId, ref);
				} else {
					// session+ref known, increase ref counter
					ref.addRef();
				}
				log.debug("RefCount for {} is now: {}", refId, ref.getRefCount());
			}
		}
	}

	/**
	 * Removes a callback reference from the internal reference storage
	 *
	 * @param sessionId the related session id
	 * @param refId     the reference id of the callback
	 */
	synchronized void removeCallbackRef(long sessionId, String refId) {

		log.debug("Releasing {}", refId);
		synchronized (sessionRefCount) {

			Map<String, RemoteRefContainer> sessionMap = sessionRefCount.get(sessionId);

			if (sessionMap == null) {
				log.debug("Session {} has no refs available. Something went wrong! Ref to release: {}", Utils.longToHexString(sessionId), refId);
			} else {
				RemoteRefContainer ref = sessionMap.get(refId);

				if (ref != null) {
					int oldCount = ref.getRefCount();
					;
					int newCount = ref.removeRef();

					log.debug("new count for ref {} is: {}; was: {}", refId, newCount, oldCount);

					if (newCount == 0) {
						sessionMap.remove(refId);

						log.trace("session map now contains {} items", sessionMap.size());
						if (sessionMap.isEmpty()) {
							sessionRefCount.remove(sessionId);
							log.trace("{} sessions have references", sessionRefCount.size());
						}
					}
				} else {
					log.warn("Something went wrong: ref {} not found in sessionmap on session {}", refId, Utils.longToHexString(sessionId));
				}
			}
			releaseRemoteBinding(refId);
			synchronized (gcRemoteInstances) {
				List<String> list = gcRemoteInstances.get(sessionId);
				if (list != null) {
					boolean remove = list.remove(refId);
					log.debug("Removed {} from list of gcRemoteInstance for session {}", refId, sessionId);
				}
			}
		}
	}


	/**
	 * This method is used by the {@link Dispatcher} and the
	 * {@link ProcessMessageRunnable} class when sending a
	 * {@link SimonRemoteInstance}. Calling this method will store the simon
	 * remote instance for later GC along with the session. This is necessary
	 * for the DGC to release all remote instances which are related to a
	 * specific {@link IoSession}. The remote instance is also stored as a
	 * remote binding.
	 *
	 * @param sessionId           the id from {@link IoSession#getId()} from the related
	 *                            {@link IoSession}
	 * @param simonRemoteInstance the related SimonRemoteInstance
	 * @param remoteObject        the remote object that has been found in a method
	 *                            argument or method result
	 */
	synchronized void putRemoteInstance(long sessionId, SimonRemoteInstance simonRemoteInstance, Object remoteObject) {
		log.debug("begin");

		String sriRemoteObjectName = simonRemoteInstance.getId();

		log.debug("sessionId={} sriRemoteObjectName={} remoteObject={}", Utils.longToHexString(sessionId), sriRemoteObjectName, remoteObject);

		addCallbackRef(sessionId, sriRemoteObjectName, remoteObject);

		// list ob remote object names that need to be GC'ed somewhen later
		List<String> remoteObjectNames;

		// if there no list present, create one
		if (!gcRemoteInstances.containsKey(sessionId)) {
			log.debug("session '{}' unknown, creating new remote instance list!", Utils.longToHexString(sessionId));
			remoteObjectNames = new ArrayList<>();
			gcRemoteInstances.put(sessionId, remoteObjectNames);
		} else {
			remoteObjectNames = gcRemoteInstances.get(sessionId);
		}
		/*
		 * if remote is not already known, add it to list
		 * This check is useful when you provide one and the same callback object to server many times.
		 * There the name is always the same. And when unreferencing the object get's unreferenced once.
		 */
		if (!remoteObjectNames.contains(sriRemoteObjectName)) {
			remoteObjectNames.add(sriRemoteObjectName);

			putRemoteBinding(sriRemoteObjectName, remoteObject);

			log.debug("session '{}' now has {} entries.", Utils.longToHexString(sessionId), remoteObjectNames.size());
		} else {
			log.debug("sriRemoteObjectName={} already known. Skipping.", sriRemoteObjectName);
		}
		log.debug("end");
	}

	/**
	 * Gets a already bind remote object according to the given remote object
	 * name
	 *
	 * @param remoteObjectName the name of the object we are interested in
	 * @return the remote object container
	 * @throws LookupFailedException if remote object is not available in lookup
	 *                               table
	 */
	RemoteObjectContainer getRemoteObjectContainer(String remoteObjectName) throws LookupFailedException {
		log.debug("begin");
		synchronized (bindings) {
			if (!bindings.containsKey(remoteObjectName)) {
				log.debug("remote object name=[{}] not found in LookupTable!", remoteObjectName);
				throw new LookupFailedException(
						"remoteobject with name [" + remoteObjectName + "] not found in lookup table.");
			}

			log.debug("name={} resolves to object='{}'", remoteObjectName, bindings.get(remoteObjectName));

			log.debug("end");
			return bindings.get(remoteObjectName);
		}
	}

	/**
	 * Frees a saved remote object. After a remote object is freed, it cannot be
	 * looked up again until it's bound again.
	 *
	 * @param name the remote object to free
	 */
	synchronized void releaseRemoteBinding(String name) {

		log.debug("begin");
		log.debug("name={}", name);

		synchronized (bindings) {
			RemoteObjectContainer remoteObjectContainer = bindings.remove(name);

			// remoteObject may be null in case of multithreaded access
			// to Simon#unbind() and thus releaseRemoteBinding()
			if (remoteObjectContainer != null) {
				Object remoteObject = remoteObjectContainer.getRemoteObject();
				log.debug("cleaning up [{}]", remoteObject);
				removeRemoteObjectFromSet(remoteObject);
				log.debug("Removing {} from remoteObject_to_hashToMethod_Map", remoteObject);
				Map<Long, Method> remove = remoteObject_to_hashToMethod_Map.remove(remoteObject);
				if (remove == null) {
					log.error("Object {} NOT removed from remoteObject_to_hashToMethod_Map. ROC={}", remoteObject, remoteObjectContainer);
				}
			} else {
				log.debug("[{}] already removed or not available. nothing to do.", name);
			}
		}

		log.debug("end");
	}

	/**
	 * Removes the given object from the set of remote objects
	 *
	 * @param remoteObject object to remove
	 */
	private void removeRemoteObjectFromSet(@NotNull Object remoteObject) {
		int hashCode = remoteObject.hashCode();
		log.debug("remoteObject={} hash={} map={}", remoteObject, hashCode, remoteobjectSet);
		boolean removed = remoteobjectSet.remove(remoteObject);
		if (!removed) {
			log.error("Object NOT removed!");
		}
		log.trace("Removed remote object {} with hash={}; removed={}", remoteObject, hashCode, removed);
	}

	/**
	 * Gets a method according to the given remote object name and method hash
	 * value
	 *
	 * @param remoteObjectName the remote object name which contains the method
	 * @param methodHash   the hash of the method
	 * @return the method
	 */
	public synchronized Method getMethod(String remoteObjectName, long methodHash) {
		log.debug("begin");

		final RemoteObjectContainer remoteObjectContainer = bindings.get(remoteObjectName);
		if (remoteObjectContainer != null) {
			final Object remoteObject = remoteObjectContainer.getRemoteObject();
			if (remoteObject != null) {
				final Map<Long, Method> remoteObjectMethods = remoteObject_to_hashToMethod_Map.get(remoteObject);
				if (remoteObjectMethods != null) {
					final Method method = remoteObjectMethods.get(methodHash);
					log.debug("hash={} resolves to method='{}'", methodHash, method);
					log.debug("end");
					return method;
				}
			}
		}

		log.debug("Can't resolve method={} for remoteObjectName={}", methodHash, remoteObjectName);
		log.debug("end");
		return null;
	}

	/**
	 * Computes for each method of the given remote object a method has and save
	 * this in an internal map for later lookup
	 *
	 * @param remoteClass the class that contains the methods
	 * @return a map that holds the methods hash as the key and the method
	 * itself as the value
	 */
	private HashMap<Long, Method> computeMethodHashMap(Class<?> remoteClass) {
		log.debug("begin");

		log.debug("computing for remoteclass='{}'", remoteClass);

		HashMap<Long, Method> map = new HashMap<>();

		for (Class<?> cl = remoteClass; cl != null; cl = cl.getSuperclass()) {

			log.debug("examin superclass='{}' for interfaces", cl);

			for (Class<?> intf : cl.getInterfaces()) {
				log.debug("examin superclass' interface='{}'", intf);

				for (Method method : intf.getMethods()) {
					/*
					 * Set this Method object to override language
					 * access checks so that the dispatcher can invoke
					 * methods from non-public remote interfaces.
					 */
					method.setAccessible(true);
					long methodHash = Utils.computeMethodHash(method);
					map.put(methodHash, method);
					log.debug("computing hash: method='{}' hash={}", method, methodHash);
				}
			}
		}

		log.debug("begin");
		return map;
	}

	/**
	 * Clears the whole {@link LookupTable}
	 */
	void cleanup() {
		log.debug("begin");
		Simon.unregisterLookupTable(this);

		for (Long aLong : gcRemoteInstances.keySet()) {
			unreference(aLong);
		}

		sessionRefCount.clear();

		bindings.clear();
		remoteObject_to_hashToMethod_Map.clear();
		sessionRefCount.clear();
		cleanupDone = true;
		log.debug("end");
	}

	/**
	 * Removes remote instance objects from {@link LookupTable}. If the remote
	 * object implements the interface {@link SimonUnreferenced}, the
	 * {@link SimonUnreferenced#unreferenced()} method is finally called.
	 *
	 * @param sessionId the id from {@link IoSession#getId()} from the related
	 *                  {@link IoSession}
	 */
	void unreference(long sessionId) {
		String id = Utils.longToHexString(sessionId);
		log.debug("begin. sessionId={} cleanupDone={}", id, cleanupDone);

		List<String> list;
		synchronized (gcRemoteInstances) {
			list = gcRemoteInstances.remove(sessionId);
		}
		synchronized (sessionRefCount) {
			sessionRefCount.remove(sessionId);
		}

		if (list != null) {

			if (log.isDebugEnabled()) {
				log.debug("sessionId={} There are {} remote instances to be unreferenced.", id, list.size());
			}

			for (String remoteObjectName : list) {

				if (log.isDebugEnabled()) {
					log.debug("sessionId={} Unreferencing: {}", id, remoteObjectName);
				}

				synchronized (bindings) {
					RemoteObjectContainer container = bindings.remove(remoteObjectName);
					log.debug("sessionId={} RemoteObjectContainer to unreference: {}", id, container);

					if (container != null) {
						Object remoteInstanceBindingToRemove = container.getRemoteObject();
						log.debug("sessionId={} simon remote to unreference: {}", id, remoteInstanceBindingToRemove);

						removeRemoteObjectFromSet(remoteInstanceBindingToRemove);

						remoteObject_to_hashToMethod_Map.remove(remoteInstanceBindingToRemove);

						if (remoteInstanceBindingToRemove instanceof SimonUnreferenced remoteBinding) {
							remoteBinding.unreferenced();
							log.debug("sessionId={} Called the unreferenced() method on {}", id, remoteInstanceBindingToRemove);
						}
					} else {
						log.debug("Container for {} no longer present?", remoteObjectName);
					}
				}
			}
		}
		log.debug("end. sessionId={} ", id);
	}

	/**
	 * Returns the related Dispatcher
	 *
	 * @return related dispatcher
	 */
	Dispatcher getDispatcher() {
		return dispatcher;
	}

	/**
	 * Checks whether the provided object is registered in the remote object
	 * hashmap
	 *
	 * @param remoteObject
	 * @return true, if the given object is registered, false if not
	 */
	boolean isSimonRemoteRegistered(Object remoteObject) {
		if (remoteObject == null) {
			return false;
		}
		log.trace("searching hash {} in {}", remoteObject.hashCode(), remoteobjectSet);
		if (remoteobjectSet.contains(remoteObject)) {
			return true;
		}
		return false;
	}

	/**
	 * Gets a already bind remote object according to the given remote interface
	 * name
	 *
	 * @param interfaceName then name of the interface to query for
	 * @return the corresponding <code>RemoteObjectContainer</code>
	 * @throws LookupFailedException if nothing was found, or if the found
	 *                               result is not unique
	 */
	synchronized RemoteObjectContainer getRemoteObjectContainerByInterface(String interfaceName) throws LookupFailedException {
		RemoteObjectContainer foundContainer = null;

		// Iterate over all bindings to find an remote object that implements the searched interface
		for (String remoteObjectName : bindings.keySet()) {

			RemoteObjectContainer knownContainer = bindings.get(remoteObjectName);

			for (Class<?> interfaze : knownContainer.getRemoteObjectInterfaces()) {

				if (interfaze.getName().equals(interfaceName)) {

					// check uniqueness of container
					if (foundContainer == null) {
						foundContainer = knownContainer;
					} else {
						if (foundContainer.getRemoteObject() != knownContainer.getRemoteObject()) {
							throw new LookupFailedException(
									"No unique '" + interfaceName + "' interface implementation found in bindings.");
						}
					}
				}
			}
		}

		if (foundContainer == null) {
			throw new LookupFailedException("No '" + interfaceName + "' interface implementation found");
		}

		return foundContainer;
	}

	/* *************************************
	 *              JMX Stuff
	 * *************************************/

	@Override
	public int getNumberOfRemoteRefSessions() {
		log.debug("{}", toString());
		return sessionRefCount.size();
	}

	@Override
	public Long[] getRemoteRefSessions() {
		return sessionRefCount.keySet().toArray(new Long[0]);
	}

	@Override
	public String[] getRefIdsForSession(long sessionId) {
		return sessionRefCount.get(sessionId).keySet().toArray(new String[0]);
	}

	@Override
	public int getRemoteRefCount(long sessionId, String refId) {
		return sessionRefCount.get(sessionId).get(refId).getRefCount();
	}

	@Override
	public int getTotalRefCount() {
		int i = 0;
		synchronized (sessionRefCount) {
			for (Long sessionId : sessionRefCount.keySet()) {
				Map<String, RemoteRefContainer> refMap = sessionRefCount.get(sessionId);
				Collection<RemoteRefContainer> values = refMap.values();
				for (RemoteRefContainer remoteRef : values) {
					i += remoteRef.getRefCount();
				}
			}
		}
		return i;
	}

	@Override
	public List<String> getCallbackRefList() {
		final List<String> list = new ArrayList<>();

		synchronized (sessionRefCount) {
			for (Long sessionId : sessionRefCount.keySet()) {
				Map<String, RemoteRefContainer> refMap = sessionRefCount.get(sessionId);
				Collection<RemoteRefContainer> values = refMap.values();
				for (RemoteRefContainer remoteRef : values) {
					list.add("Session: " + Utils.longToHexString(sessionId) + " -> " + remoteRef.toString());
				}
			}
		}
		return list;
	}
}
