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

import host.anzo.simon.LookupTable;
import host.anzo.simon.Simon;
import host.anzo.simon.Statics;
import host.anzo.simon.codec.messages.AbstractMessage;
import host.anzo.simon.codec.messages.MsgError;
import host.anzo.simon.codec.messages.MsgInvoke;
import host.anzo.simon.codec.messages.SimonMessageConstants;
import host.anzo.simon.utils.SimonClassLoaderHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.demux.MessageDecoder;

import java.lang.reflect.Method;
import java.nio.charset.Charset;

/**
 * A {@link MessageDecoder} that decodes {@link MsgInvoke}.
 *
 * @author ACHR
 */
@Slf4j
public class MsgInvokeDecoder extends AbstractMessageDecoder {
	public MsgInvokeDecoder() {
		super(SimonMessageConstants.MSG_INVOKE);
	}

	@Override
	protected AbstractMessage decodeBody(IoSession session, IoBuffer in) {

		MsgInvoke msgInvoke = new MsgInvoke();
		String remoteObjectName = null;
		try {

			LookupTable lookupTable = (LookupTable) session.getAttribute(Statics.SESSION_ATTRIBUTE_LOOKUPTABLE);

			log.trace("start pos={} capacity={}", in.position(), in.capacity());
			remoteObjectName = in.getPrefixedString(Charset.forName("UTF-8").newDecoder());
			msgInvoke.setRemoteObjectName(remoteObjectName);
			log.trace("remote object name read ... remoteObjectName={} pos={}", remoteObjectName, in.position());

			final long methodHash = in.getLong();
			log.trace("got method hash {}", methodHash);

			final Method method = lookupTable.getMethod(msgInvoke.getRemoteObjectName(), methodHash);
			if (method == null) {
				return null;
			}

			log.trace("method looked up ... pos={} method=[{}]", in.position(), method);

			int argsLength = in.getInt();
			log.trace("args len read read ... pos={}", in.position());
			log.trace("getting {} args", argsLength);
			Object[] args = new Object[argsLength];
			for (int i = 0; i < argsLength; i++) {
				try {
					args[i] = in.getObject(SimonClassLoaderHelper.getClassLoader(Simon.class));
				} catch (Exception ex) {
					Exception ex1 = new Exception("Problem reading method argument. Maybe argument isn't serializable?!");
					ex1.initCause(ex.getCause());
					ex1.setStackTrace(ex.getStackTrace());
					log.error("Exception while reading arguments.", ex);
					throw ex1;
				}
				log.trace("arg #{} read ... pos={} object={}", i, in.position(), args[i]);
			}

			msgInvoke.setArguments(args);
			msgInvoke.setRemoteObjectName(remoteObjectName);
			msgInvoke.setMethod(method);
		} catch (Exception e) {
			MsgError error = new MsgError();
			error.setErrorMessage("Error while decoding invoke request");
			error.setRemoteObjectName(remoteObjectName);
			error.setThrowable(e);
			return error;
		}

		log.trace("message={}", msgInvoke);
		return msgInvoke;
	}

	@Override
	public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
	}
}
