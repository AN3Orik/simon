/*
 * Copyright © 2016 BDO-Emu authors. All rights reserved.
 * Viewing, editing, running and distribution of this software strongly prohibited.
 * Author: xTz, Anton Lasevich, Tibald
 */

package host.anzo.simon.utils;

import host.anzo.simon.SimonProxy;
import host.anzo.simon.SimonRemoteMarker;
import host.anzo.simon.annotation.SimonRemote;
import host.anzo.simon.codec.base.SimonProtocolCodecFactory;
import host.anzo.simon.exceptions.IllegalRemoteObjectException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.*;

/**
 * A class with some static helper-methods
 *
 * @author ACHR
 */
@Slf4j
public class Utils {
	/**
	 * if this flag is set to TRUE, SIMON tries to load the java.util.logging
	 * properties and enabled the debug-mode
	 *
	 * @deprecated use JVM argument
	 * "java.util.logging.config.file=./log/mylogconfig.properties"
	 */
	@Deprecated
	public static boolean DEBUG = false;
	/**
	 * A map that memories some method hashes so that they need not to be
	 * re-generated each time the hash is used. If memory is getting short, some
	 * entries are gc'ed so that more memory is available. There is no need to
	 * clear the map ourselves.
	 */
	private static final WeakHashMap<Method, Long> methodHashes = new WeakHashMap<Method, Long>();

	private static final String SIMON_REMOTE_ANNOTATION_CLASSNAME = SimonRemote.class.getName();

	/**
	 * Compute the "method hash" of a remote method. The method hash is a long
	 * containing the first 64 bits of the SHA digest from the bytes
	 * representing the complete method signature.
	 *
	 * @param m the method for which the hash has to be computed
	 * @return the computed hash
	 */
	public static long computeMethodHash(Method m) {
		Long hash;
		synchronized (methodHashes) {
			hash = methodHashes.get(m);
		}
		if (hash != null) {
			log.trace("Got hash from map. map contains {} entries.", methodHashes.size());
			return hash;
		} else {
			long result = 0;
			ByteArrayOutputStream byteArray = new ByteArrayOutputStream(127);

			try {
				MessageDigest md = MessageDigest.getInstance("SHA");

				DigestOutputStream out = new DigestOutputStream(byteArray, md);

				// use the complete method signature to generate the sha-digest
				out.write(m.toGenericString().getBytes());

				// use only the first 64 bits of the digest for the hash
				out.flush();
				byte hasharray[] = md.digest();
				for (int i = 0; i < Math.min(8, hasharray.length); i++) {
					result += ((long) (hasharray[i] & 0xFF)) << (i * 8);
				}
			} catch (IOException ignore) {
				// can't really happen
				result = -1;
			} catch (NoSuchAlgorithmException complain) {
				throw new SecurityException(complain.getMessage());
			}

			synchronized (methodHashes) {
				methodHashes.put(m, result);
				log.trace("computed new hash. map now contains {} entries.", methodHashes.size());
			}

			return result;
		}
	}

	/**
	 * Loads a protocol codec factory by a given classname
	 *
	 * @param protocolFactory a class name like
	 *                        "com.mydomain.myproject.codec.mySimonProtocolCodecFactory" which points
	 *                        to a class, that extends {@link SimonProtocolCodecFactory}. <i>The
	 *                        important thing is, that this class correctly overrides
	 *                        {@link SimonProtocolCodecFactory#setup(boolean)}. For further details,
	 *                        look at {@link SimonProtocolCodecFactory}!</i>
	 * @return the protocolcodecfactory instance according to the given protocol
	 * factory class name
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
	public static SimonProtocolCodecFactory getProtocolFactoryInstance(String protocolFactory) throws ClassNotFoundException, InstantiationException, IllegalAccessException {
		Class<?> clazz = Class.forName(protocolFactory);
		try {
			return (SimonProtocolCodecFactory) clazz.newInstance();
		} catch (ClassCastException e) {
			throw new ClassCastException("The given class '" + protocolFactory + "' must extend '" +
					SimonProtocolCodecFactory.class.getCanonicalName() + "' !");
		}
	}

	/**
	 * Converts a long value to a hex string, i.e. 0xF923
	 *
	 * @param l
	 * @return return a string showing the hex value of parameter l
	 */
	public static String longToHexString(long l) {

		StringBuilder id = new StringBuilder();
		id.append(Long.toHexString(l).toUpperCase());

		while (id.length() < 8) {
			id.insert(0, "0");
		}
		id.insert(0, "0x");

		return id.toString();
	}

	/**
	 * Converts a boolean value to a byte value.
	 *
	 * @param bool
	 * @return 0xFF if true, 0x00 if false
	 */
	public static byte booleanToByte(boolean bool) {
		return (bool ? (byte) 0xFF : (byte) 0x00);
	}

	/**
	 * Converts a byte value to a boolean value.
	 *
	 * @param b
	 * @return 0xFF if true, 0x00 if false
	 * @throws IllegalArgumentException if byte value not 0xFF or 0x00
	 */
	public static boolean byteToBoolean(byte b) throws IllegalArgumentException {
		switch (b) {
			case (byte) 0xFF:
				return true;

			case (byte) 0x00:
				return false;

			default:
				throw new IllegalArgumentException("only 0xFF and 0x00 value allowed for 'byte-to-boolean' conversion!");
		}
	}

	/**
	 * Method that returns an Class array containing all remote interfaces of
	 * a given class
	 *
	 * @param clazz the class to analyze for remote interfaces
	 * @return the array with all known remote interfaces
	 */
	public static Class<?> @NotNull [] findAllRemoteInterfaces(Class<?> clazz) {
		Set<Class<?>> interfaceSet = doFindAllRemoteInterfaces(clazz);

		Class<?>[] interfaces = new Class[interfaceSet.size()];
		if (log.isTraceEnabled()) {
			log.trace("found interfaces: {}", Arrays.toString(interfaceSet.toArray(interfaces)));
		}
		return interfaceSet.toArray(interfaces);
	}

	/**
	 * Internal helper method for finding remote interfaces
	 *
	 * @param clazz the class to analyse for remote interfaces
	 * @return a set with remote interfaces
	 */
	private static @NotNull Set<Class<?>> doFindAllRemoteInterfaces(Class<?> clazz) {
		Set<Class<?>> interfaceSet = new HashSet<>();

		if (clazz == null) {
			return interfaceSet;
		}

		String type = (clazz.isInterface() ? "interface" : "class");

		// check for annotation in clazz
		final SimonRemote annotation = clazz.getAnnotation(SimonRemote.class);
		if (annotation != null) {
			log.trace("SimonRemote annotation found for {} {}", type, clazz.getName());

			// check for remote interfaces specified in the SimonRemote annotation
			Class<?>[] remoteInterfaces = annotation.value();

			if (remoteInterfaces.length > 0) {
				/*
				 * found some specified remote interfaces in the annotation's value field.
				 * Use them and return
				 */
				log.trace("SimonRemote annotation has remote interfaces specified. Adding: {}", Arrays.toString(remoteInterfaces));
				Collections.addAll(interfaceSet, remoteInterfaces);
			} else {
				/*
				 * there is no interfaces specified with the annotation's value field.
				 * Using all visible interfaces from the class as a remote interface.
				 * "All" means: All first-level interfaces, independant from any SimonRemote annotation or extension
				 * If class itself is an interface, just use this
				 */
				if (clazz.isInterface()) {
					interfaceSet.add(clazz);
				}

				Collections.addAll(interfaceSet, clazz.getInterfaces());
			}
		} else { // deeper search

			log.trace("No SimonRemote annotation found for {} {}. Searching for interfaces that extend SimonRemoteMarker or use SimonRemote Annotation.", type, clazz.getName());
			/*
			 * There's no initial annotation
			 * Need to search for a Interface in any superclass/superinterface that extends SimonRemote
			 */

			// go through all interfaces
			for (Class<?> interfaze : clazz.getInterfaces()) {

				// check interfaces for remote
				if (interfaze.isAnnotationPresent(SimonRemote.class)) {
					// interface is annotated
					interfaceSet.add(interfaze);

					// Check for parent interfaces (made with 'interface extends another interface')
					for (Class<?> parentInterface : interfaze.getInterfaces()) {
						interfaceSet.addAll(doFindAllRemoteInterfaces(parentInterface));
					}
				} else {
					// no remote interface found
					// checking for super interface
					interfaceSet.addAll(doFindAllRemoteInterfaces(interfaze.getSuperclass()));
				}
			}

			// check also super classes
			if (clazz.getSuperclass() != null && !clazz.getSuperclass().equals(Object.class)) {
				interfaceSet.addAll(doFindAllRemoteInterfaces(clazz.getSuperclass()));
			}
		}
		return interfaceSet;
	}

	/**
	 * Checks whether the object is annotated with
	 * <code>SimonRemote</code> or not
	 *
	 * @param remoteObject the object to check
	 * @return true, if object is annotated, false if not
	 */
	public static boolean isRemoteAnnotated(Object remoteObject) {
		if (remoteObject == null) {
			throw new IllegalArgumentException("Cannot check a null-argument. You have to provide a proxy object instance ...");
		}
		boolean isRemoteAnnotated = remoteObject.getClass().isAnnotationPresent(SimonRemote.class);

		// if annotation is not found via current CL, try again with remoteobject's CL (but only if it's not the Bootstrap-CL (which means null)
		// see: http://dev.root1.de/issues/173
		if (!isRemoteAnnotated) {

			// get CL only once, as getting CL requires a native call --> avoid too many JNI calls
			ClassLoader remoteObjectCL = remoteObject.getClass().getClassLoader();

			if (remoteObjectCL != null) {
				try {
					isRemoteAnnotated = remoteObject.getClass().isAnnotationPresent((Class<? extends Annotation>) remoteObjectCL.loadClass(SIMON_REMOTE_ANNOTATION_CLASSNAME));
				} catch (ClassNotFoundException ex) {
					// simply ignore
				}
			}
		}
		return isRemoteAnnotated;
	}

	/**
	 * Returns the value of the
	 * <code>SimonRemote</code> annotation.
	 *
	 * @param remoteObject the object to query
	 * @return the annotation value
	 * @throws IllegalArgumentException in case of remoteObject==null
	 */
	public static Class<?>[] getRemoteAnnotationValue(Object remoteObject) {
		if (remoteObject == null) {
			throw new IllegalArgumentException("Cannot check a null-argument. You have to provide a proxy object instance ...");
		}
		return remoteObject.getClass().getAnnotation(SimonRemote.class).value();
	}

	/**
	 * Checks if the given remote object is a valid remote object. Checks for:
	 * <ul>
	 * <li>SimonRemote annotation</li>
	 * <li>SimonRemoteMarker proxy</li>
	 * <li>implements SimonRemote</li>
	 * </ul>
	 *
	 * @param remoteObject the object to check
	 * @return true, if remote object is valid, false if not
	 * @throws IllegalRemoteObjectException thrown in case of a faulty remote
	 *                                      object (ie. missing interfaces)
	 */
	public static boolean isValidRemote(Object remoteObject) {

		if (remoteObject == null) {
			return false;
		}
		if (isRemoteAnnotated(remoteObject)) {

			if (remoteObject.getClass().getInterfaces().length > 0 ||
					getRemoteAnnotationValue(remoteObject).length > 0) {
				return true;
			} else {
				throw new IllegalRemoteObjectException("There is no interface with the remote object of type '" +
						remoteObject.getClass().getCanonicalName() +
						"' linked. Add a 'value' parameter with array of interfaces (at least one interface) to the SimonRemote annotation, or let the class implement an interface");
			}
		}
		if (getMarker(remoteObject) != null) {
			return true;
		}
		return false;
	}

	/**
	 * Checks if given object is a simon proxy.
	 *
	 * @param o object to check
	 * @return true, if object is a simon proxy, false if not
	 */
	public static boolean isSimonProxy(Object o) {
		if (o instanceof Proxy) {
			InvocationHandler invocationHandler = Proxy.getInvocationHandler(o);
			log.trace("Got invocation handler ...");
			if (invocationHandler instanceof SimonProxy) {
				log.trace("Yeeha. It's a SimonProxy ...");
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the related instance of {@link SimonRemoteMarker} of the given
	 * object. if the specified object isn't marked, null is returned.
	 *
	 * @param o
	 * @return the related instance of {@link SimonRemoteMarker}, or null if
	 * given object is not marked
	 */
	public static SimonRemoteMarker getMarker(Object o) {
		if (o instanceof Proxy) {
			InvocationHandler invocationHandler = Proxy.getInvocationHandler(o);
			if (invocationHandler instanceof SimonRemoteMarker) {
				return (SimonRemoteMarker) invocationHandler;
			}
		}
		return null;
	}

	/**
	 * Small helper method that pushes all interfaces of the specified class to
	 * the specified stack
	 *
	 * @param stack
	 * @param clazz
	 */
	private static void putInterfacesToStack(Stack<Class> stack, @NotNull Class clazz) {
		Class[] interfaces = clazz.getInterfaces();
		for (Class iClazz : interfaces) {
			stack.push(iClazz);
		}
	}

	/**
	 * Reads all interfaces and subinterfaces of the given object and add the
	 * names to the provided interface name list
	 *
	 * @param object         the object to search for interfaces
	 * @param interfaceNames the list to which found interfaces names are added
	 */
	public static void putAllInterfaceNames(@NotNull Object object, List<String> interfaceNames) {
		Stack<Class> stack = new Stack<>();
		Utils.putInterfacesToStack(stack, object.getClass());
		while (!stack.empty()) {
			Class iClazz = stack.pop();
			String iClazzName = iClazz.getCanonicalName();
			log.trace("Adding {} to the list of remote interfaces", iClazzName);
			if (!interfaceNames.contains(iClazzName)) {
				interfaceNames.add(iClazzName);
			}
			Utils.putInterfacesToStack(stack, iClazz);
		}
	}

	/**
	 * Returns the stacktrace of the given throwable as a string. String will be
	 * the same as "e.printStackTrace();" woulld print to console
	 *
	 * @param e
	 * @return the exceptions stacktrace as a string
	 */
	public static String getStackTraceAsString(Throwable e) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString();
	}

	/**
	 * Looks up and returns the root cause of an exception. If none is found,
	 * returns supplied Throwable object unchanged. If root is found,
	 * recursively "unwraps" it, and returns the result to the caller.
	 *
	 * @param th
	 * @return the exceptions root-cause, if available, otherwise th will be
	 * returned unchanged
	 */
	public static Throwable getRootCause(Throwable th) {
		if (th instanceof SAXException) {
			SAXException sax = (SAXException) th;
			if (sax.getException() != null) {
				return getRootCause(sax.getException());
			}
		} else if (th instanceof SQLException) {
			SQLException sql = (SQLException) th;
			if (sql.getNextException() != null) {
				return getRootCause(sql.getNextException());
			}
		} else if (th.getCause() != null) {
			return getRootCause(th.getCause());
		}

		return th;
	}

	/**
	 * Retrieve object hash code and applies a supplemental hash function to the
	 * result hash, which defends against poor quality hash functions. This is
	 * critical because HashMap uses power-of-two length hash tables, that
	 * otherwise encounter collisions for hashCodes that do not differ in lower
	 * bits. Note: Null keys always map to hash 0, thus index 0.
	 */
	public static final int hash(Object object) {

		if (object == null) {
			return 0;
		}

		int hash = 0;

		hash ^= object.hashCode();

		// This function ensures that hashCodes that differ only by
		// constant multiples at each bit position have a bounded
		// number of collisions (approximately 8 at default load factor).
		hash ^= (hash >>> 20) ^ (hash >>> 12);
		return hash ^ (hash >>> 7) ^ (hash >>> 4);
	}

	/**
	 * Check whether the current VM is a Android DalvikVM or not
	 *
	 * @return true, is applications runs on Androids DalvikVM, false if not
	 */
	private static boolean isDalvikVM() {

		// java.vm.specification.name=Dalvik Virtual Machine Specification
		// AND
		// java.vm.vendor=The Android Project

		if (System.getProperty("java.vm.specification.name", "").equals("Dalvik Virtual Machine Specification") &&
				System.getProperty("java.vm.vendor", "").equals("The Android Project")) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * see MBeanServer#registerMBean(Object, ObjectName) This is a workaround to
	 * be able to run the code also on android, where the MBeanServer is not
	 * available
	 *
	 * @return true, if registration succeeds, false if not
	 */
	public static boolean registerMBean(Object o, String objectNameOfMBean) {

		if (isDalvikVM()) {
			log.info("Running on Android. Skipping registration on MBeanServer for [{}]", objectNameOfMBean);
			return false;
		}

		try {
			// ManagementFactory#getPlatformMBeanServer()
			String cnManagementFactory = "java.lang.management.ManagementFactory";
			String mnGetPlatformMBeanServer = "getPlatformMBeanServer";
			Class<?> cManagementFactory = Class.forName(cnManagementFactory);
			Method mGetPlatformMBeanServer = cManagementFactory.getDeclaredMethod(mnGetPlatformMBeanServer);
			Object oMBeanServer = mGetPlatformMBeanServer.invoke(null);

			// create ObjectName object
			String cnObjectName = "javax.management.ObjectName";
			Class<?> cObjectName = Class.forName(cnObjectName);
			Constructor<?> constructor = cObjectName.getConstructor(String.class);
			Object oObjectName = constructor.newInstance(objectNameOfMBean);

			// MBeanServer#registerMBean(this, ObjectName)
			String cnMBeanServer = "javax.management.MBeanServer";
			String mnRegisterMBean = "registerMBean";
			Class<?> cMBeanServer = Class.forName(cnMBeanServer);
			Method mRegisterMBean = cMBeanServer.getMethod(mnRegisterMBean, Object.class,
					cObjectName);
			mRegisterMBean.invoke(oMBeanServer, o,
					oObjectName);
			return true;
		} catch (Throwable t) {
			log.warn("Cannot register [{}] on MBeanServer.", objectNameOfMBean, t);
			return false;
		}
	}
}
