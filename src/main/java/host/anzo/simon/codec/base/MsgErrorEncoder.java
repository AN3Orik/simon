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
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.demux.MessageEncoder;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

/**
 * A {@link MessageEncoder} that encodes {@link MsgError}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgErrorEncoder<T extends MsgError> extends AbstractMessageEncoder<T> {
	@Override
	protected void encodeBody(IoSession session, T message, IoBuffer out) {

		log.trace("begin. message=" + message);

		String remoteObjectName = message.getRemoteObjectName();
		if (remoteObjectName == null) {
			remoteObjectName = "<NoRemoteObjectNameAvailable>";
		}
		String errorMsg = message.getErrorMessage();
		if (errorMsg == null) {
			errorMsg = "<NoErrorMsgAvailable>";
		}
		Throwable throwable = message.getThrowable();
		if (throwable == null) {
			throwable = new Throwable("NoThrowableAvailable");
		}
		int initSequenceId = message.getInitSequenceId();
		boolean isDecodeError = message.isDecodeError();

		try {
			out.putPrefixedString(remoteObjectName, Charset.forName("UTF-8").newEncoder());
			out.putPrefixedString(errorMsg, Charset.forName("UTF-8").newEncoder());
		} catch (CharacterCodingException e) {
			// TODO what to do here?
		}
		out.putObject(throwable);
		out.putInt(initSequenceId);
		out.put(Utils.booleanToByte(isDecodeError));

		log.trace("end");
	}
}
