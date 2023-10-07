// Copyright (c) 2013-2023 xipki. All rights reserved.
// License Apache License 2.0

package org.xipki.ca.gateway.est.servlet3;

import org.xipki.ca.gateway.est.servlet.EstHttpFilter;
import org.xipki.servlet3.ServletFilter;
import org.xipki.util.exception.ServletException0;
import org.xipki.util.http.XiHttpFilter;

import javax.servlet.FilterConfig;

/**
 * EST Gateway ServletFilter.
 *
 * @author Lijun Liao (xipki)
 * @since 6.0.0
 */
public class EstServletFilter extends ServletFilter {

  @Override
  protected XiHttpFilter initFilter(FilterConfig filterConfig) throws ServletException0 {
     return new EstHttpFilter();
  }

}
