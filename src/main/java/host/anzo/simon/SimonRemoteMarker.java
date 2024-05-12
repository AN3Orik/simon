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

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;


/**
 * This is a marker-interface to mark a class as a remote class
 *
 * @author achr
 * @since 1.1.0
 */
@Slf4j
public class SimonRemoteMarker implements InvocationHandler {
	/**
	 * TODO: document me!
	 */
	private final Object objectToBeMarked;

	/**
	 * TODO document me!
	 *
	 * @param objectToBeMarked
	 */
	protected SimonRemoteMarker(Object objectToBeMarked) {
		this.objectToBeMarked = objectToBeMarked;
	}

	/**
	 * TODO document me!
	 *
	 * @return objectToBeMarked
	 */
	protected Object getObjectToBeMarked() {
		return objectToBeMarked;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		log.trace("objectToBeMarked={} method={} args.length={}", new Object[]{
				objectToBeMarked.getClass().getCanonicalName(),
				method.getName(),
				(args == null ? 0 : args.length)
		});
		return method.invoke(objectToBeMarked, args);
	}
}
