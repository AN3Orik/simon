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
package host.anzo.simon.utils;

// inspired by http://www.javaworld.com/javaworld/javaqa/2003-06/01-qa-0606-load.html
public class SimonClassLoaderHelper {
	public static ClassLoader getClassLoader(Class<?> c) {
		return getClassLoader(c, null);
	}

	/**
	 * Returns best possible classloader for the specified
	 * <code>caller</code> class. Best means:
	 * <p>
	 * Ensure that the returning CL is the CL at the best possible bottom, so
	 * that class loading requests wil be processed from absolut bottom to the
	 * absolute top (system CL). This guarantees that no CL is cut off in CL hierarchie
	 * <p>
	 * If
	 * <code>specialClassloader</code> is also specified, the CL search algorith
	 * starts the search with this one, instead of
	 * <code>caller</code>
	 *
	 * @param caller
	 * @param specialClassLoader
	 * @return ClassLoader
	 */
	public static ClassLoader getClassLoader(Class<?> caller, ClassLoader specialClassLoader) {
		final ClassLoader callerClassLoader = (
				specialClassLoader != null ? specialClassLoader : caller.getClassLoader());
		final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();

		ClassLoader result;

		// if 'callerLoader' and 'contextLoader' are in a parent-child
		// relationship, always choose the child:

		if (isChild(contextClassLoader, callerClassLoader)) {
			result = callerClassLoader;
		} else if (isChild(callerClassLoader, contextClassLoader)) {
			result = contextClassLoader;
		} else {
			// no relationship at all...
			result = specialClassLoader != null ? specialClassLoader : contextClassLoader;
		}

		final ClassLoader systemLoader = ClassLoader.getSystemClassLoader();

		// precaution for when deployed as a bootstrap or extension class:
		if (isChild(result, systemLoader)) {
			result = systemLoader;
		}

		return result;
	}

	/**
	 * Returns 'true' if 'loader2' is a delegation child of 'loader1' [or if
	 * 'loader1'=='loader2']. Of course, this works only for classloaders that
	 * set their parent pointers correctly. 'null' is interpreted as the
	 * primordial loader [i.e., everybody's parent].
	 */
	private static boolean isChild(final ClassLoader loader1, ClassLoader loader2) {

		if (loader1 == loader2) {
			return true;
		}

		if (loader2 == null) {
			return false;
		}

		if (loader1 == null) {
			return true;
		}

		for (; loader2 != null; loader2 = loader2.getParent()) {
			if (loader2 == loader1) {
				return true;
			}
		}

		return false;
	}
}
