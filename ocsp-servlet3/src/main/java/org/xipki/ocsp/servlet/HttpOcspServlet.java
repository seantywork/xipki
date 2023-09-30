// Copyright (c) 2013-2023 xipki. All rights reserved.
// License Apache License 2.0

package org.xipki.ocsp.servlet;

import org.xipki.ocsp.server.servlet.HttpOcspServlet0;
import org.xipki.servlet3.HttpRequestMetadataRetrieverImpl;
import org.xipki.servlet3.ServletHelper;
import org.xipki.util.Args;
import org.xipki.util.http.RestResponse;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * HTTP servlet of the OCSP responder.
 *
 * @author Lijun Liao (xipki)
 * @since 3.0.1
 */

public class HttpOcspServlet extends HttpServlet {

  private HttpOcspServlet0 underlying;

  public void setUnderlying(HttpOcspServlet0 underlying) {
    this.underlying = Args.notNull(underlying, "undelying");
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      RestResponse restResp = underlying.doPost(new HttpRequestMetadataRetrieverImpl(req), req.getInputStream());
      ServletHelper.fillResponse(restResp, resp);
    } finally {
      resp.flushBuffer();
    }
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    try {
      RestResponse restResp = underlying.doGet(new HttpRequestMetadataRetrieverImpl(req));
      ServletHelper.fillResponse(restResp, resp);
    } finally {
      resp.flushBuffer();
    }
  }

}
