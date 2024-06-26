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

import host.anzo.simon.exceptions.RawChannelException;
import host.anzo.simon.exceptions.SimonRemoteException;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.session.IoSession;

import java.nio.ByteBuffer;

/**
 * This class enables one to send raw data from one station to a remote station.<br>
 * A simple example on how to transfer a file from a client to the server:<br>
 * Let's assume that the client has already looked up a remote object. Let's
 * call it <code>server</code>. The server needs to know that the client wants
 * to transfer a file. So the server should offer a method like this:<br>
 * <pre><code>
 * // method in the server object
 * public int openFileChannel() throws SimonRemoteException {
 *    return Simon.prepareRawChannel(myRawChannelDataListener, this);
 * }</code></pre>
 * <p>
 * The result of the <code>prepareRawChannel()</code> call will be an integer,
 * called <code>channelToken</code>, which, as the name implies, identifies the
 * prepared raw channel:
 * <p>
 * <pre><code>
 * // called on the client
 * int channelToken = server.openFileChannel();</code></pre>
 * <p>
 * With this channel token, the client can now open the raw channel:
 * <p>
 * <pre><code>
 * // called on the client
 * RawChannel rawChannel = Simon.openRawChannel(channelToken, server);</code></pre>
 * <p>
 * Now the client can send raw data (in shape of a {@link ByteBuffer}) to the
 * server by calling <code>rawChannel.write()</code>, where the registered
 * <code>myRawChannelDataListener</code> on the server side handles the received
 * data. If the transmission has finished, just call
 * <code>rawChannel.close()</code>, and the listener on the remote station will
 * recognize it too and can finally close the file output stream.<br>
 * <br>
 * <i>A final word on"why do all this stress with this raw channel. Why don't send the data by just calling a server method?"
 * :</i> Each method call on a remote object involves a lot of reflection and
 * serialization stuff, which results on a more or less time consuming behavior.
 * Using the raw channel mechanism, there's almost no reflection and no serialization needed, and
 * the data is transfered directly, without method lookups and so on. So the
 * performance should be better, at least with an increasing amount of data that
 * needs to be transferred.
 *
 * @author achr
 */
@Slf4j
public class RawChannel {
	private final Dispatcher dispatcher;
	private final IoSession session;
	private final int channelToken;
	private boolean channelOpen = true;

	/**
	 * Instantiates a new raw channel. This is done by calling {@link Simon#openRawChannel(int, Object)}.
	 *
	 * @param dispatcher   the related {@link Dispatcher}
	 * @param session      the related {@link IoSession}
	 * @param channelToken the channeltoken we got from the remote station by calling {@link Simon#prepareRawChannel(RawChannelDataListener, Object)}
	 */
	protected RawChannel(Dispatcher dispatcher, IoSession session, int channelToken) {
		log.debug("token={}", channelToken);
		this.dispatcher = dispatcher;
		this.session = session;
		this.channelToken = channelToken;
	}

	/**
	 * Writes the given buffer (position 0 up to current position) to the server. This method can be called several
	 * times. The system ensures that the data receives in the order it was
	 * sent. <i><b>Note:</b> Each buffer has to be wrapped by the SIMON protocol. The
	 * overhead is about 9 byte per write() call. So you should not send too
	 * small packets, otherwise you have some bandwidth loss!</i><br>
	 * <b>Note:</b> Calling this method will block until the server received the data!
	 *
	 * @param byteBuffer the buffer who's content is written to the server
	 * @throws IllegalStateException if the channel is already closed.
	 * @throws SimonRemoteException
	 * @throws RawChannelException   if the remote {@link RawChannelDataListener#write(ByteBuffer)} has problems writing.
	 */
	public void write(ByteBuffer byteBuffer) throws IllegalStateException, SimonRemoteException, RawChannelException {
		if (channelOpen) {
			log.trace("token={}. channel open. forwarding to dispatcher ...", channelToken);
			dispatcher.writeRawData(session, channelToken, byteBuffer);
			log.trace("token={}. data forwarded", channelToken);
		} else {
			throw new IllegalStateException("Instance of RawChannel already closed!");
		}
	}

	/**
	 * Return <code>true</code>, is channel is already closed and <code>false</code> is still open and <i>useable</i>.
	 *
	 * @return closed-state of channel
	 */
	public boolean isClosed() {
		return !channelOpen;
	}

	/**
	 * Signals on the remote station that the transmission has finished. This
	 * also closes the raw channel. So after calling this method, each write()
	 * call fails!
	 *
	 * @throws IllegalStateException if the channel is already closed.
	 * @throws SimonRemoteException
	 * @throws RawChannelException   if the remote {@link RawChannelDataListener#close()} has problems closing.
	 */
	public void close() throws IllegalStateException, SimonRemoteException, RawChannelException {
		if (channelOpen) {
			dispatcher.closeRawChannel(session, channelToken);
			channelOpen = false;
		} else {
			throw new IllegalStateException("Instance of RawChannel already closed!");
		}
	}
}
