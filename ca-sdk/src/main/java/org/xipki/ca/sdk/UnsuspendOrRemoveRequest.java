// Copyright (c) 2013-2023 xipki. All rights reserved.
// License Apache License 2.0

package org.xipki.ca.sdk;

import org.xipki.ca.sdk.jacob.CborDecoder;
import org.xipki.ca.sdk.jacob.CborEncoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 *
 * @author Lijun Liao (xipki)
 * @since 6.0.0
 */

public class UnsuspendOrRemoveRequest extends CaIdentifierRequest {

  private BigInteger[] entries;

  public void setEntries(BigInteger[] entries) {
    this.entries = entries;
  }

  public BigInteger[] getEntries() {
    return entries;
  }

  @Override
  public void encode(CborEncoder encoder) throws EncodeException {
    super.encode(encoder, 1);
    try {
      if (entries == null) {
        encoder.writeNull();
      } else {
        encoder.writeArrayStart(entries.length);
        for (BigInteger v : entries) {
          encoder.writeByteString(v);
        }
      }
    } catch (IOException ex) {
      throw new EncodeException("error decoding " + getClass().getName(), ex);
    }
  }

  public static UnsuspendOrRemoveRequest decode(byte[] encoded) throws DecodeException {
    try (CborDecoder decoder = new CborDecoder(new ByteArrayInputStream(encoded))){
      if (decoder.readNullOrArrayLength(4)) {
        return null;
      }

      UnsuspendOrRemoveRequest ret = new UnsuspendOrRemoveRequest();
      ret.setIssuerCertSha1Fp(decoder.readByteString());
      ret.setIssuer(X500NameType.decode(decoder));
      ret.setAuthorityKeyIdentifier(decoder.readByteString());
      ret.setEntries(decoder.readBigInts());
      return ret;
    } catch (IOException ex) {
      throw new DecodeException("error decoding " + UnsuspendOrRemoveRequest.class.getName(), ex);
    }
  }


}
