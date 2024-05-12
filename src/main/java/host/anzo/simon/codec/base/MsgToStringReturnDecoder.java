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
import host.anzo.simon.codec.messages.MsgError;
import host.anzo.simon.codec.messages.MsgToStringReturn;
import host.anzo.simon.codec.messages.SimonMessageConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.demux.MessageDecoder;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

/**
 * A {@link MessageDecoder} that decodes {@link MsgToStringReturn}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgToStringReturnDecoder extends AbstractMessageDecoder {
	public MsgToStringReturnDecoder() {
		super(SimonMessageConstants.MSG_TOSTRING_RETURN);
	}

	@Override
	protected AbstractMessage decodeBody(IoSession session, IoBuffer in) {
		MsgToStringReturn message = new MsgToStringReturn();
		try {
			String returnValue = in.getPrefixedString(Charset.forName("UTF-8").newDecoder());
			message.setReturnValue(returnValue);
		} catch (CharacterCodingException e) {
			MsgError error = new MsgError();
			error.setErrorMessage("Error while decoding toString() return: Not able to read result due to CharacterCodingException");
			error.setRemoteObjectName(null);
			error.setThrowable(e);
			return error;
		}
		log.trace("message={}", message);
		return message;
	}

	@Override
	public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
	}
}