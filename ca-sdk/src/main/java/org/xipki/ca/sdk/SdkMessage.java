// Copyright (c) 2013-2023 xipki. All rights reserved.
// License Apache License 2.0

package org.xipki.ca.sdk;

import org.xipki.ca.sdk.jacob.CborDecoder;
import org.xipki.ca.sdk.jacob.CborEncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 *
 * @author Lijun Liao (xipki)
 * @since 6.0.0
 */

public abstract class SdkMessage {

  public abstract void encode(CborEncoder encoder) throws EncodeException;

  public byte[] encode() throws EncodeException {
    try (ByteArrayOutputStream bout = new ByteArrayOutputStream()) {
      CborEncoder encoder = new CborEncoder(bout);
      encode(encoder);
      bout.flush();
      return bout.toByteArray();
    } catch (IOException e) {
      throw new EncodeException("error encoding " + getClass().getName(), e);
    }
  }

}
