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

import host.anzo.simon.codec.messages.MsgEquals;
import host.anzo.simon.codec.messages.MsgError;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.demux.MessageEncoder;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

/**
 * A {@link MessageEncoder} that encodes {@link MsgEquals}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgEqualsEncoder<T extends MsgEquals> extends AbstractMessageEncoder<T> {
	@Override
	protected void encodeBody(IoSession session, T message, IoBuffer out) {

		log.trace("begin. message={}", message);
		try {
			out.putPrefixedString(message.getRemoteObjectName(), Charset.forName("UTF-8").newEncoder());
			out.putObject(message.getObjectToCompareWith());
		} catch (CharacterCodingException e) {
			MsgError error = new MsgError();
			error.setEncodeError();
			error.setErrorMessage("Error while encoding equals() request: Not able to write remote object name '" +
					message.getRemoteObjectName() + "' due to CharacterCodingException.");
			error.setRemoteObjectName(message.getRemoteObjectName());
			error.setInitSequenceId(message.getSequence());
			error.setThrowable(e);
			sendEncodingError(out, session, error);
		}
		log.trace("end");
	}
}
