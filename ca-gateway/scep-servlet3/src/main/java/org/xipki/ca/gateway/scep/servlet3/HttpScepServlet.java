// Copyright (c) 2013-2023 xipki. All rights reserved.
// License Apache License 2.0

package org.xipki.ca.gateway.scep.servlet3;

import org.xipki.ca.gateway.scep.servlet.HttpScepServlet0;
import org.xipki.servlet3.HttpRequestMetadataRetrieverImpl;
import org.xipki.servlet3.ServletHelper;
import org.xipki.util.http.RestResponse;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * SCEP servlet.
 *
 * <p>URL http://host:port/scep/&lt;name&gt;/&lt;profile-alias&gt;/pkiclient.exe
 *
 * @author Lijun Liao (xipki)
 * @since 6.0.0
 */

public class HttpScepServlet extends HttpServlet {

  private HttpScepServlet0 underlying;

  public void setUnderlying(HttpScepServlet0 underlying) {
    this.underlying = underlying;
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    RestResponse restResp = underlying.doGet(new HttpRequestMetadataRetrieverImpl(req));
    ServletHelper.fillResponse(restResp, resp);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    RestResponse restResp = underlying.doPost(new HttpRequestMetadataRetrieverImpl(req), req.getInputStream());
    ServletHelper.fillResponse(restResp, resp);
  }

}
