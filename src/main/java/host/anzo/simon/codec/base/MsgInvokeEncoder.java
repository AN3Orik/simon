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

import host.anzo.simon.Dispatcher;
import host.anzo.simon.Statics;
import host.anzo.simon.codec.messages.MsgInvoke;
import host.anzo.simon.codec.messages.MsgInvokeReturn;
import host.anzo.simon.exceptions.SimonRemoteException;
import host.anzo.simon.utils.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.demux.MessageEncoder;

import java.nio.charset.Charset;

/**
 * A {@link MessageEncoder} that encodes {@link MsgInvoke}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgInvokeEncoder<T extends MsgInvoke> extends AbstractMessageEncoder<T> {
	@Override
	protected void encodeBody(IoSession session, T message, IoBuffer out) {

		log.trace("begin. message={}", message);
		try {

			out.putPrefixedString(message.getRemoteObjectName(), Charset.forName("UTF-8").newEncoder());
			out.putLong(Utils.computeMethodHash(message.getMethod()));

			int argsLen = 0;

			if (message.getArguments() != null) {
				argsLen = message.getArguments().length;
			}

			log.trace("argsLength={}", argsLen);

			out.putInt(argsLen);

			for (int i = 0; i < argsLen; i++) {
				log.trace("args[{}]={}", i, message.getArguments()[i]);
				out.putObject(message.getArguments()[i]);
			}
		} catch (Exception e) {

			String errorMsg = "Failed to transfer invoke command to the server. error=" + e.getMessage();
			log.warn(errorMsg);
			Dispatcher dispatcher = (Dispatcher) session.getAttribute(Statics.SESSION_ATTRIBUTE_DISPATCHER);

			MsgInvokeReturn mir = new MsgInvokeReturn();
			mir.setSequence(message.getSequence());
			mir.setReturnValue(new SimonRemoteException(errorMsg, e));

			try {
				dispatcher.messageReceived(session, mir);
			} catch (Exception e1) {
				// FIXME When will this Exception occur? API Doc shows no information
				log.error("Got exception when calling 'dispatcher.messageReceived()'", e1);
			}
		}
		log.trace("end");
	}
}