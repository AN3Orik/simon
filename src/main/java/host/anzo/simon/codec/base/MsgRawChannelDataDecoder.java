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
import host.anzo.simon.codec.messages.MsgRawChannelData;
import host.anzo.simon.codec.messages.SimonMessageConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.demux.MessageDecoder;

import java.nio.ByteBuffer;

/**
 * A {@link MessageDecoder} that decodes {@link MsgRawChannelData}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgRawChannelDataDecoder extends AbstractMessageDecoder {
	public MsgRawChannelDataDecoder() {
		super(SimonMessageConstants.MSG_RAW_CHANNEL_DATA);
	}

	@Override
	protected AbstractMessage decodeBody(IoSession session, IoBuffer in) {
		MsgRawChannelData message = new MsgRawChannelData();

		message.setSequence(getCurrentSequence());

		int dataSize = getBodySize() - 4; // not counting the 4 bytes from the channel token
		log.trace("datasize={}", dataSize);

		int channelToken = in.getInt();
		log.trace("channelToken={}", channelToken);
		message.setChannelToken(channelToken);

		byte[] b = new byte[dataSize];
		in.get(b);
		message.setData(ByteBuffer.allocate(dataSize).put(b));
		log.trace("message={}", message);

		return message;
	}

	@Override
	public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
	}
}
