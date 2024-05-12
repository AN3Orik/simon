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
import host.anzo.simon.codec.messages.MsgNameLookupReturn;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.demux.MessageEncoder;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

/**
 * A {@link MessageEncoder} that encodes {@link MsgNameLookupReturn}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgNameLookupReturnEncoder<T extends MsgNameLookupReturn> extends AbstractMessageEncoder<T> {
	@Override
	protected void encodeBody(IoSession session, T message, IoBuffer out) {
		log.trace("sending interfaces ...");

		String[] interfaces = message.getInterfacesString();
		out.putInt(interfaces.length);
		log.trace("interfaces to send: {}", interfaces.length);
		for (String class1 : interfaces) {
			try {
				log.trace("interface={}", class1);
				out.putPrefixedString(class1, Charset.forName("UTF-8").newEncoder());
			} catch (CharacterCodingException e) {
				MsgError error = new MsgError();
				error.setEncodeError();
				error.setErrorMessage(
						"Error while encoding name lookup() return: Not able to write interface name '" + class1 +
								"' due to CharacterCodingException.");
				error.setRemoteObjectName(null);
				error.setInitSequenceId(message.getSequence());
				error.setThrowable(e);
				sendEncodingError(out, session, error);
			}
		}
		try {
			log.trace("sending erorMsg: '{}'", message.getErrorMsg());
			out.putPrefixedString(message.getErrorMsg(), Charset.forName("UTF-8").newEncoder());
		} catch (CharacterCodingException e) {
			MsgError error = new MsgError();
			error.setEncodeError();
			error.setErrorMessage(
					"Error while encoding name lookup() return: Not able to write errorMsg '" + message.getErrorMsg() +
							"' due to CharacterCodingException.");
			error.setRemoteObjectName(null);
			error.setInitSequenceId(message.getSequence());
			error.setThrowable(e);
			sendEncodingError(out, session, error);
		}

		log.trace("finished");
	}
}
