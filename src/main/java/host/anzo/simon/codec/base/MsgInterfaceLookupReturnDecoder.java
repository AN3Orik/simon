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

import host.anzo.simon.codec.messages.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.demux.MessageDecoder;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

/**
 * A {@link MessageDecoder} that decodes {@link MsgNameLookupReturn}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgInterfaceLookupReturnDecoder extends AbstractMessageDecoder {
	public MsgInterfaceLookupReturnDecoder() {
		super(SimonMessageConstants.MSG_INTERFACE_LOOKUP_RETURN);
	}

	@Override
	protected AbstractMessage decodeBody(IoSession session, IoBuffer in) {

		log.trace("decoding ...");
		MsgInterfaceLookupReturn m = new MsgInterfaceLookupReturn();

		try {
			String remoteObjectName = in.getPrefixedString(Charset.forName("UTF-8").newDecoder());
			int arraySize = in.getInt();
			log.trace("trying to read interfaces value. num of interfaces: {}", arraySize);
			String[] interfaces = new String[arraySize];
			for (int i = 0; i < arraySize; i++) {
				String iface = in.getPrefixedString(Charset.forName("UTF-8").newDecoder());
				interfaces[i] = iface;
				log.trace("got interface=[{}]", interfaces[i]);
			}
			m.setErrorMsg(in.getPrefixedString(Charset.forName("UTF-8").newDecoder()));
			m.setInterfaces(interfaces);
			m.setRemoteObjectName(remoteObjectName);
		} catch (CharacterCodingException e) {
			MsgError error = new MsgError();
			error.setErrorMessage("Error while decoding interrace lookup return: Not able to read interface name due to CharacterCodingException.");
			error.setRemoteObjectName(null);
			error.setThrowable(e);
			return error;
		}

		log.trace("finished");
		return m;
	}

	@Override
	public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
	}
}
