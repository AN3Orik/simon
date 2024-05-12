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
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.demux.MessageDecoder;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

/**
 * A {@link MessageDecoder} that decodes {@link MsgNameLookup}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgInterfaceLookupDecoder extends AbstractMessageDecoder {
	public MsgInterfaceLookupDecoder() {
		super(SimonMessageConstants.MSG_INTERFACE_LOOKUP);
	}

	@Override
	protected AbstractMessage decodeBody(IoSession session, IoBuffer in) {

		MsgInterfaceLookup m = new MsgInterfaceLookup();

		try {
			String canonicalInterfaceName = in.getPrefixedString(Charset.forName("UTF-8").newDecoder());
			m.setCanonicalInterfaceName(canonicalInterfaceName);
		} catch (CharacterCodingException e) {
			MsgError error = new MsgError();
			error.setErrorMessage("Error while decoding interrace lookup: Not able to read interface name due to CharacterCodingException.");
			error.setRemoteObjectName(null);
			error.setThrowable(e);
			return error;
		}

		if (log.isTraceEnabled())
			log.trace("message={} on session={}", m, Utils.longToHexString(session.getId()));

		return m;
	}

	@Override
	public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
	}
}
