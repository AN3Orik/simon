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

import org.apache.mina.core.service.IoServiceStatistics;

/**
 * A simple implementation of {@link SimonRegistryStatistics}
 *
 * @author alexanderchristian
 */
public class RegistryStatistics implements SimonRegistryStatistics {

	private IoServiceStatistics ioServiceStatistics;

	protected RegistryStatistics(IoServiceStatistics ioServiceStatistics) {
		this.ioServiceStatistics = ioServiceStatistics;
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLargestManagedSessionCount()
	 */
	public int getLargestManagedSessionCount() {
		return ioServiceStatistics.getLargestManagedSessionCount();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getCumulativeManagedSessionCount()
	 */
	public long getCumulativeManagedSessionCount() {
		return ioServiceStatistics.getCumulativeManagedSessionCount();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLastIoTime()
	 */
	public long getLastIoTime() {
		return ioServiceStatistics.getLastIoTime();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLastReadTime()
	 */
	public long getLastReadTime() {
		return ioServiceStatistics.getLastReadTime();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLastWriteTime()
	 */
	public long getLastWriteTime() {
		return ioServiceStatistics.getLastWriteTime();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getReadBytes()
	 */
	public long getReadBytes() {
		return ioServiceStatistics.getReadBytes();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getWrittenBytes()
	 */
	public long getWrittenBytes() {
		return ioServiceStatistics.getWrittenBytes();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getReadMessages()
	 */
	public long getReadMessages() {
		return ioServiceStatistics.getReadMessages();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getWrittenMessages()
	 */
	public long getWrittenMessages() {
		return ioServiceStatistics.getWrittenMessages();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getReadBytesThroughput()
	 */
	public double getReadBytesThroughput() {
		return ioServiceStatistics.getReadBytesThroughput();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getWrittenBytesThroughput()
	 */
	public double getWrittenBytesThroughput() {
		return ioServiceStatistics.getWrittenBytesThroughput();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getReadMessagesThroughput()
	 */
	public double getReadMessagesThroughput() {
		return ioServiceStatistics.getReadMessagesThroughput();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getWrittenMessagesThroughput()
	 */
	public double getWrittenMessagesThroughput() {
		return ioServiceStatistics.getWrittenMessagesThroughput();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLargestReadBytesThroughput()
	 */
	public double getLargestReadBytesThroughput() {
		return ioServiceStatistics.getLargestReadBytesThroughput();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLargestWrittenBytesThroughput()
	 */
	public double getLargestWrittenBytesThroughput() {
		return ioServiceStatistics.getLargestWrittenBytesThroughput();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLargestReadMessagesThroughput()
	 */
	public double getLargestReadMessagesThroughput() {
		return ioServiceStatistics.getLargestReadMessagesThroughput();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getLargestWrittenMessagesThroughput()
	 */
	public double getLargestWrittenMessagesThroughput() {
		return ioServiceStatistics.getLargestWrittenMessagesThroughput();
	}

	/**
	 * Returns the interval (seconds) between each throughput calculation.
	 * The default value is {@code 3} seconds.
	 */
	public int getThroughputCalculationInterval() {
		return ioServiceStatistics.getThroughputCalculationInterval();
	}

	/**
	 * Returns the interval (milliseconds) between each throughput calculation.
	 */
	public long getThroughputCalculationIntervalInMillis() {
		return ioServiceStatistics.getThroughputCalculationIntervalInMillis();
	}

	/**
	 * Sets the interval (seconds) between each throughput calculation.
	 */
	public void setThroughputCalculationInterval(int throughputCalculationInterval) {
		ioServiceStatistics.setThroughputCalculationInterval(throughputCalculationInterval);
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getScheduledWriteBytes()
	 */
	public long getScheduledWriteBytes() {
		return ioServiceStatistics.getScheduledWriteBytes();
	}

	/* (non-Javadoc)
	 * @see host.anzo.simon.SimonStatistics#getScheduledWriteMessages()
	 */
	public long getScheduledWriteMessages() {
		return ioServiceStatistics.getScheduledWriteMessages();
	}
}
