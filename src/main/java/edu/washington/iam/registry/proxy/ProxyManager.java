/* ========================================================================
 * Copyright (c) 2012 The University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ========================================================================
 */

package edu.washington.iam.registry.proxy;

import java.io.Serializable;
import java.util.List;

import org.w3c.dom.Document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import edu.washington.iam.registry.exception.ProxyException;
import edu.washington.iam.registry.exception.NoPermissionException;

public interface ProxyManager extends Serializable {
   public List<Proxy> getProxys();
   public Proxy getProxy(String entityId);
   public int removeProxy(String rpid, String updatedBy) throws ProxyException;
   public List<Proxy> getProxyHistory(String entityId) throws ProxyException;
    ;

    /**
     *
     * @param proxy Takes a validated proxy and stores it
     */
   public void updateProxy(Proxy proxy, String updatedBy) throws ProxyException;

}
