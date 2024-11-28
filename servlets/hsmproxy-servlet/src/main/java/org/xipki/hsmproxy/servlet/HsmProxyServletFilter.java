// Copyright (c) 2013-2024 xipki. All rights reserved.
// License Apache License 2.0

package org.xipki.hsmproxy.servlet;

import jakarta.servlet.FilterConfig;
import org.xipki.servlet.ServletFilter;
import org.xipki.util.http.XiHttpFilter;

/**
 * The Servlet Filter of HSM proxy servlets.
 *
 * @author Lijun Liao (xipki)
 */

public class HsmProxyServletFilter extends ServletFilter {

  @Override
  protected XiHttpFilter initFilter(FilterConfig filterConfig) throws Exception {
    return new org.xipki.hsmproxy.HsmProxyServletFilter();
  }

}