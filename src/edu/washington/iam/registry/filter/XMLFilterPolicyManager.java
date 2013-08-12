/* ========================================================================
 * Copyright (c) 2010-2011 The University of Washington
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

package edu.washington.iam.registry.filter;

import java.util.List;
import java.util.Vector;
import java.util.Collections;
import java.util.Properties;
import java.io.File;
import java.net.URI;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;


import org.w3c.dom.Node;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.washington.iam.registry.ws.RelyingPartyController;
import edu.washington.iam.tools.XMLHelper;
import edu.washington.iam.registry.exception.FilterPolicyException;
import edu.washington.iam.registry.exception.AttributeException;
import edu.washington.iam.registry.exception.AttributeNotFoundException;
import edu.washington.iam.registry.exception.NoPermissionException;

public class XMLFilterPolicyManager implements FilterPolicyManager {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ReentrantReadWriteLock locker = new ReentrantReadWriteLock();

    private List<FilterPolicyGroup> filterPolicyGroups;

    private List<Attribute> attributes;
    private String attributeUri;
    private String attributeSourceName;
    private int attributeRefresh = 0;  // seconds

    private List<Properties> policyGroupSources;
    private String tempUri = "file:/tmp/fp.xml";

    Thread reloader = null;
    private long modifyTime = 0; // for the attrs

    public List<AttributeFilterPolicy> getFilterPolicies(String rpid) {
       log.debug("looking for fps for " + rpid);
       List<AttributeFilterPolicy> list = new Vector();

       for (int g=0; g<filterPolicyGroups.size(); g++) {
          List<AttributeFilterPolicy> fps = filterPolicyGroups.get(g).getFilterPolicies();
          for (int p=0; p<fps.size(); p++) {
             AttributeFilterPolicy fp = fps.get(p);
             if (fp.matches(rpid)) {
                log.debug("  adding " + fp.getEntityId());
                list.add(fp);
             }
          }
       }
       // Collections.sort(list, new FilterPolicyComparator());
       log.info("fp search found "+list.size());
       return list;
    }

    // returns list of all attributes, policy set where matches rp
    public List<Attribute> getAttributes(String rpid) {
       List<Attribute> ret = new Vector();
       log.debug("getting editable attributes for " + rpid);
       List<AttributeFilterPolicy> fps = getFilterPolicies(rpid);
       int matches = 0;
       for (int i=0; i<attributes.size(); i++) {
          Attribute attr = attributes.get(i);
          for (int p=0; p<fps.size(); p++) {
             AttributeFilterPolicy afp = fps.get(p);
             for (int a=0; a<afp.getAttributeRules().size(); a++) {
                if (afp.getAttributeRules().get(a).getId().equals(attr.getId())) {
                   attr.setAttributeFilterPolicy(afp);
                   attr.setAttributeRule(afp.getAttributeRules().get(a));
                   matches++;
                }
             }
          }
          ret.add(attr);
       }
       log.debug("from " + attributes.size() + ", found " + matches + " matches");
       return ret;
    }

    /*
     * Update policies from an API PUT. 
     * simplified document
     */
    public void updateRelyingParty(String pgid, Document doc, String remoteUser)
             throws FilterPolicyException, AttributeNotFoundException, NoPermissionException {

       log.info("rp update attr doc for " + pgid);

       FilterPolicyGroup policyGroup = getPolicyGroup(pgid);
       if (policyGroup==null) throw new FilterPolicyException("policy group not found");
       if (!policyGroup.isEditable()) throw new FilterPolicyException("policy group not editable");
       
       policyGroup.refreshPolicyIfNeeded();
       // process each policy ( will be only one requirement rule )
       List<Element> pols = XMLHelper.getElementsByName(doc.getDocumentElement(), "AttributeFilterPolicy");
       for (int i=0; i<pols.size(); i++) {
          Element pol = pols.get(i);
          Element reqRule = XMLHelper.getElementByName(pol, "PolicyRequirementRule");
          if (reqRule==null) throw new FilterPolicyException("invalid post");

          // type assumed
          String rpid = reqRule.getAttribute("value");
          log.debug("attr update, pol=" + pgid + ", rp=" + rpid);
          AttributeFilterPolicy afp = policyGroup.getFilterPolicy(rpid);
          if (afp==null) {
              afp = new AttributeFilterPolicy(pgid, rpid);
              policyGroup.getFilterPolicies().add(afp);
          }

          List<Element> attrs = XMLHelper.getElementsByName(pol, "AttributeRule");
          for (int j=0; j<attrs.size(); j++) {
             Element attrEle = attrs.get(j);
             String attributeId = attrEle.getAttribute("attributeID");
             String act = attrEle.getAttribute("action");
             Attribute attribute = getAttribute(attributeId);

             log.debug(".." + act + " " + attributeId);

             if (act.equals("replace")) afp.replaceAttributeRule(attributeId, attrEle);
             else if (act.equals("remove")) afp.removeAttributeRule(attributeId);
             else throw new FilterPolicyException("unknown action");
          }
       }
 
       // save the new doc
       policyGroup.writePolicyGroup();
    }

    public int removeRelyingParty(String entityId, String pgid)
         throws FilterPolicyException, AttributeNotFoundException, NoPermissionException {
       FilterPolicyGroup policyGroup = getPolicyGroup(pgid);
       return policyGroup.removeFilterPolicy(entityId);
    }

    public void addAttributeRule(String policyGroupId, String entityId, String attributeId, String type, String value, String remoteUser)
           throws FilterPolicyException, AttributeNotFoundException, NoPermissionException {
       Attribute attribute = getAttribute(attributeId);
       FilterPolicyGroup pg = getPolicyGroup(policyGroupId);
       pg.addAttribute(entityId, attributeId, type, value);
    }

    public void removeAttributeRule(String pgid, String entityId, String attributeId, String type, String value, String remoteUser)
         throws FilterPolicyException, AttributeNotFoundException, NoPermissionException {
       Attribute attribute = getAttribute(attributeId);
       FilterPolicyGroup pg = getPolicyGroup(pgid);
       pg.removeAttribute(entityId, attributeId, type, value);
    }

    private FilterPolicyGroup getPolicyGroup(String pgid) {
       for (int g=0; g<filterPolicyGroups.size(); g++) if (filterPolicyGroups.get(g).getId().equals(pgid)) return filterPolicyGroups.get(g);
       return null;
    }

    private void loadAttributes() {
       attributes = new Vector();
       DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
       builderFactory.setNamespaceAware(false);
       Document doc;

       try {
          DocumentBuilder builder = builderFactory.newDocumentBuilder();
          doc = builder.parse (attributeUri);
       } catch (Exception e) {
          log.error("parse issue: " + e);
          return;
       }
       // update the timestamp
       File f = new File(attributeSourceName);
       modifyTime = f.lastModified();
       log.debug("attr load " + f.getName() + ": time = " + modifyTime);

       List<Element> list = XMLHelper.getElementsByName(doc.getDocumentElement(), "Attribute");
       log.info("found " + list.size());

       for (int i=0; i<list.size(); i++) {
          Element fpe = list.get(i);
          try {
             attributes.add(new Attribute(fpe));
          } catch (AttributeException e) {
             log.error("load of element failed: " + e);
          }
       }

    }

    // attribute reloader
    class AttributeReloader extends Thread {
        
        public void run() {
           log.debug("attr reloader running: interval = " + attributeRefresh);

           while (true) {
              log.debug("reloader checking...");
              File f = new File(attributeSourceName);
              if (f.lastModified()>modifyTime) {
                 // reload the attributes
                 log.debug("reload starting for " + attributeUri);
                 locker.writeLock().lock();
                 try {
                    loadAttributes();
                 } catch (Exception e) {
                    log.error("reload errro: " + e);
                 }
                 locker.writeLock().unlock();
                 log.debug("reload completed, time now " + modifyTime);
              }
              try {
                 if (isInterrupted()) {
                    log.info("interrupted during processing");
                    break;
                 }
                 Thread.sleep(attributeRefresh * 1000);
              } catch (InterruptedException e) {
                 log.info("sleep interrupted");
                 break;
              }
           }
        }

    }


    private void loadPolicyGroups() {
       filterPolicyGroups = new Vector();
       DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
       builderFactory.setNamespaceAware(true);

       // load policyGroups from each source
       for (int p=0; p<policyGroupSources.size(); p++) {
          try {
             FilterPolicyGroup pg = new FilterPolicyGroup(policyGroupSources.get(p));
             filterPolicyGroups.add(pg);
          } catch (FilterPolicyException e) {
             log.error("could not load policy group ");
          }
       }

    }

    // find an attribute
    public Attribute getAttribute(String id) throws AttributeNotFoundException {
       for (int i=0; i<attributes.size(); i++) {
          if (attributes.get(i).getId().equals(id)) return attributes.get(i);
       }
       throw new AttributeNotFoundException();
    }
     
    public void setPolicyGroupSources(List<Properties> v) {
       policyGroupSources = v;
    }

    public List<FilterPolicyGroup> getFilterPolicyGroups() {
       return filterPolicyGroups;
    }

    public List<Attribute> getAttributes() {
       return attributes;
    }

    public void setAttributeUri(String v) {
       attributeUri = v;
       attributeSourceName = attributeUri.replaceFirst("file:","");
    }
    public void setTempUri(String v) {
       tempUri = v;
    }

    public void setAttributeRefresh(int i) {
       attributeRefresh = i;
    }

/**
    public void setGroupManager(GroupManager v) {
       groupManager = v;
    }
 **/

    public void init() {
       loadAttributes();
       loadPolicyGroups();

       // start attribute list refresher
       if (attributeRefresh>0) {
          reloader = new Thread(new AttributeReloader());
          reloader.start();
       }
       
    }

    public void cleanup() {
        if (reloader != null) reloader.interrupt();
    }

}
