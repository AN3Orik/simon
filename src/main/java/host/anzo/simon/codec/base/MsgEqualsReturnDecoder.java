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

import host.anzo.simon.codec.messages.AbstractMessage;
import host.anzo.simon.codec.messages.MsgEqualsReturn;
import host.anzo.simon.codec.messages.SimonMessageConstants;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.demux.MessageDecoder;

/**
 * A {@link MessageDecoder} that decodes {@link MsgEqualsReturn}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgEqualsReturnDecoder extends AbstractMessageDecoder {
	public MsgEqualsReturnDecoder() {
		super(SimonMessageConstants.MSG_EQUALS_RETURN);
	}

	@Override
	protected AbstractMessage decodeBody(IoSession session, IoBuffer in) {
		MsgEqualsReturn message = new MsgEqualsReturn();

		message.setEqualsResult(Utils.byteToBoolean(in.get()));

		log.trace("message={}", message);
		return message;
	}

	@Override
	public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
	}
}
