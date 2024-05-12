/*
 * Copyright (C) 2012 Alexander Christian <alex(at)root1.de>. All rights reserved.
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
import host.anzo.simon.exceptions.SimonException;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.List;

/**
 * A ReferenceQueue that tracks lifetime of SimonProxy objects. If proxy is
 * GC'ed, a message is sent to remote to signal that the reference-count can be
 * decreased by 1.
 *
 * @param <T> type of SimonPhantomRef
 * @author achristian
 * @since 1.2.0
 */
@Slf4j
public class SimonRefQueue<T extends SimonPhantomRef> extends ReferenceQueue<T> implements Runnable {
	private final List<Reference> refs = new ArrayList<>();
	private static final int REMOVE_TIMEOUT = 5000;
	private final Thread refCleanerThread;
	private final Dispatcher dispatcher;

	SimonRefQueue(Dispatcher dispatcher) {
		this.dispatcher = dispatcher;
		refCleanerThread = new Thread(this, "SimonRefQueue#" + hashCode());
		refCleanerThread.setDaemon(true);
		refCleanerThread.start();
	}

	public synchronized void addRef(SimonProxy simonProxy) {
		if (!refCleanerThread.isAlive() || refCleanerThread.isInterrupted()) {
			throw new IllegalStateException("refCleanerThread not longer active. Shutdown in progress?");
		}
		Reference ref = new SimonPhantomRef(simonProxy, this);
		log.debug("Adding ref: {}", ref);
		refs.add(ref);
		log.debug("Ref count after add: {}", refs.size());
	}

	@Override
	public void run() {
		while (!refCleanerThread.isInterrupted()) {
			try {
				SimonPhantomRef ref = (SimonPhantomRef) remove(REMOVE_TIMEOUT);
				if (ref != null) {
					log.debug("Releasing: {}", ref);
					refs.remove(ref);
					ref.clear();
					log.debug("Ref count after remove: {}", refs.size());
					sendRelease(ref);
				} else {
					// FIXME remove GC call here ...
					//                    if (log.isTraceEnabled()) {
					//                        log.trace("********** Trigger GC! **********");
					//                        System.gc();
					//                    }
				}
			} catch (InterruptedException ex) {
				refCleanerThread.interrupt();
			}
		}

		log.debug(Thread.currentThread().getName() + " terminated");
	}

	synchronized void cleanup() {
		log.debug("Stopping refCleanerThread");
		refCleanerThread.interrupt();

		log.debug("Sending release for {} refs", refs.size());
		while (!refs.isEmpty()) {
			// remove one by one until list is empty
			SimonPhantomRef ref = (SimonPhantomRef) refs.remove(0);
			sendRelease(ref);
		}
		// ensure it is cleared
		refs.clear();
	}

	private void sendRelease(SimonPhantomRef ref) {
		try {
			if (ref.getSession().isConnected()) {
				dispatcher.sendReleaseRef(ref.getSession(), ref.getRefId());
			} else {
				log.debug("Sending release for ref {} not possible due to closed session {}.", ref, Utils.longToHexString(ref.getSession().getId()));
			}
		} catch (SimonException ex) {
			log.warn("Not able to send a 'release ref' for " + ref, ex);
		} catch (SessionException ex) {
			log.warn("Not able to send a 'release ref' for " + ref, ex);
		}
	}
}