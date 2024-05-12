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

import host.anzo.simon.codec.messages.MsgError;
import host.anzo.simon.codec.messages.MsgToStringReturn;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.demux.MessageEncoder;

import java.nio.charset.Charset;

/**
 * A {@link MessageEncoder} that encodes {@link MsgToStringReturn}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgToStringReturnEncoder<T extends MsgToStringReturn> extends AbstractMessageEncoder<T> {
	@Override
	protected void encodeBody(IoSession session, T message, IoBuffer out) {

		log.trace("begin. message={}", message);
		try {
			out.putPrefixedString(message.getReturnValue(), Charset.forName("UTF-8").newEncoder());
		} catch (Exception e) {
			MsgError error = new MsgError();
			error.setEncodeError();
			error.setErrorMessage(
					"Error while encoding toString() request: Not able to write result'" + message.getReturnValue() +
							"' due to CharacterCodingException.");
			error.setRemoteObjectName(null);
			error.setInitSequenceId(message.getSequence());
			error.setThrowable(e);
			sendEncodingError(out, session, error);
		}
		log.trace("end");
	}
}