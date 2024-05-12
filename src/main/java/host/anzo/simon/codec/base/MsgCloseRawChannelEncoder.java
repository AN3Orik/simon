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
package host.anzo.simon.codec.base;

import host.anzo.simon.codec.messages.MsgCloseRawChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.demux.MessageEncoder;

/**
 * A {@link MessageEncoder} that encodes {@link MsgCloseRawChannel}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgCloseRawChannelEncoder<T extends MsgCloseRawChannel> extends AbstractMessageEncoder<T> {
	@Override
	protected void encodeBody(IoSession session, T message, IoBuffer out) {

		log.trace("begin. message=" + message);
		out.putInt(message.getChannelToken()); // send the channel token
		log.trace("end");
	}
}
