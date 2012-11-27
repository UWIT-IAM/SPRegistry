/* ========================================================================
 * Copyright (c) 2009-2011 The University of Washington
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

package edu.washington.iam.registry.ws;

import java.lang.Exception;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Cookie;

import edu.washington.iam.registry.rp.RelyingParty;
import edu.washington.iam.registry.rp.RelyingPartyManager;
import edu.washington.iam.registry.rp.Metadata;
import edu.washington.iam.tools.XMLHelper;
import edu.washington.iam.tools.DNSVerifier;
import edu.washington.iam.tools.DNSVerifyException;
import edu.washington.iam.tools.Group;
import edu.washington.iam.tools.GroupManager;

import edu.washington.iam.registry.filter.FilterPolicyManager;
import edu.washington.iam.registry.filter.AttributeFilterPolicy;
import edu.washington.iam.registry.filter.FilterPolicyGroup;
import edu.washington.iam.registry.filter.Attribute;

import edu.washington.iam.registry.proxy.Proxy;
import edu.washington.iam.registry.proxy.ProxyManager;

import edu.washington.iam.registry.exception.RelyingPartyException;
import edu.washington.iam.registry.exception.FilterPolicyException;
import edu.washington.iam.registry.exception.AttributeNotFoundException;
import edu.washington.iam.registry.exception.NoPermissionException;
import edu.washington.iam.registry.exception.ProxyException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;

import java.security.cert.X509Certificate;


@Controller
public class RelyingPartyController {

    private final Logger log =  LoggerFactory.getLogger(getClass());

    private FilterPolicyManager filterPolicyManager;
    private RelyingPartyManager rpManager;
    private ProxyManager proxyManager;

    private static DNSVerifier dnsVerifier;
    private static GroupManager groupManager;
    private String adminGroupName = "u_cac_internal_act-as";
    private Group adminGroup = null;

    public DNSVerifier getDnsVerifier() {
        return dnsVerifier;
    }
    public void setDnsVerifier(DNSVerifier v) {
        dnsVerifier = v;
    }
    public void setGroupManager(GroupManager v) {
        groupManager = v;
    }

    private MailSender mailSender;
    private SimpleMailMessage templateMessage;

    public void setMailSender(MailSender mailSender) {
        this.mailSender = mailSender;
    }

    public void setTemplateMessage(SimpleMailMessage templateMessage) {
        this.templateMessage = templateMessage;
    }

    private static String browserRootPath;
    private static String certRootPath;
    private static String loginCookie;
    private static String logoutUrl;

    private static String mailTo = "pubcookie@u.washington.edu";
    private static String requestMailTo = "iam-support@u.washington.edu";

    // sessions
    private String standardLoginPath = "/login";
    private String secureLoginPath = "/securelogin";
    private String googleLoginPath = "/googlelogin";
    private String incommonLoginPath = "/incommonlogin";
    private long standardLoginSec = 9*60*60;  // 9 hour session lifetime
    private long secureLoginSec = 1*60*60;  // 1 hour session lifetime
    private String googleIdentityProvider = "https://idpgateway.u.washington.edu:7443/google_sso";

    private String myEntityId = null;
    private String eppnName = "eppn";  // env var name of user eppn
    
    // key for crypt ops
    private static String cryptKey;

    class RPSession {
       private String viewType;
       private String remoteUser;
       private String rootPath;
       private String servletPath;
       private String pageType;
       private String pageTitle;
       private long ifMatch;
       private long ifNoneMatch;
       private String errorCode;
       private String errorText;
       private boolean isBrowser;
       private String xsrfCode;
       private String remoteAddr;
       private String loginMethod;
       private boolean isAdmin;
       private boolean authn2;
       private boolean isUWLogin;
       private String userIdProvider;
       private String userDisplayName;
       private ModelAndView mv;
    }

    /* send user to login chooser page */ 
    private ModelAndView loginChooserMV(HttpServletRequest request, HttpServletResponse response) {

       String rp = "";
       if (request.getPathInfo()!=null) rp = request.getPathInfo();
       String rqs = "";  
       if (request.getQueryString()!=null) rqs = "?" +  request.getQueryString();
       String red = browserRootPath + request.getServletPath() + rp + rqs;
       log.debug("no user yet: final path=" + red);

       ModelAndView mv = new ModelAndView("browser/chooser");
       mv.addObject("root", browserRootPath);
       mv.addObject("vers", request.getServletPath());
       mv.addObject("pagetype", "browser/home");
       mv.addObject("pathextra", rp + rqs);
       mv.addObject("uwloginpath", standardLoginPath);
       mv.addObject("googleloginpath", googleLoginPath); 
       mv.addObject("incommonloginpath", incommonLoginPath);
       return (mv);
    }

    private RPSession processRequestInfo(HttpServletRequest request, HttpServletResponse response) {
       return processRequestInfo(request, response, true);
    }

    private RPSession processRequestInfo(HttpServletRequest request, HttpServletResponse response, boolean canLogin) {
        RPSession session = new RPSession();
        session.isAdmin = false;
        session.isUWLogin = false;

        log.info("RP new session =============== path=" + request.getPathInfo());

        // see if logged in (browser has login cookie; cert user has cert)

        Cookie[] cookies = request.getCookies();
        if (cookies!=null) {
          for (int i=0; i<cookies.length; i++) {
            if (cookies[i].getName().equals(loginCookie)) {
               log.debug("got cookie " + cookies[i].getName());
               String cookieStr = RPCrypt.decode(cookies[i].getValue());
               String[] cookieData = cookieStr.split(";");

               if (cookieData.length==5) {

                  if (cookieData[3].charAt(0)=='2') session.authn2 = true;

                  log.debug("login time = " + cookieData[4]);
                  long cSec = new Long(cookieData[4]);
                  long nSec = new Date().getTime()/1000;
                  if (cookieData[1].indexOf("@")<0) session.isUWLogin = true;  // klugey way to know UW people
                  if (nSec<(cSec+standardLoginSec)) {
                     if ((nSec>(cSec+secureLoginSec)) && session.authn2) {
                        log.debug("secure expired");
                        session.authn2 = false;
                     }

                     // cookie OK
                     session.remoteUser = cookieData[1];
                     session.xsrfCode = cookieData[2];
                     log.debug("login for " + session.remoteUser );
                     if (session.authn2) log.debug("secure login");
                     if (adminGroup.isMember(session.remoteUser)) {
                        log.debug("is admin");
                        session.isAdmin = true;
                     }
                     break;
                  } else log.debug("cookie expired for " + cookieData[1]);
               } else {
                  log.info("bogus cookie ignored");
               }
            }
          }
        }


        if (session.remoteUser!=null) {
           // ok, is a logged in browser
           session.viewType = "browser";
           session.isBrowser = true;
           session.rootPath = browserRootPath;

        } else {
           // maybe is cert client
           // use the CN portion of the DN as the client userid
           X509Certificate[] certs = (X509Certificate[])request.getAttribute("javax.servlet.request.X509Certificate");
           if (certs != null) {
             session.viewType = "plain";
             session.isBrowser = false;
             session.rootPath = certRootPath;
             X509Certificate cert = certs[0];
             String dn = cert.getSubjectX500Principal().getName();
             session.remoteUser = dn.replaceAll(".*CN=", "").replaceAll(",.*","");
             log.info(".. remote user by cert, dn=" + dn + ", cn=" + session.remoteUser);
/*** If we wanted the altnames, here's how
             Collection altNames = cert.getSubjectAlternativeNames();
             if (altNames!=null) {
                for (Iterator i = altNames.iterator(); i.hasNext(); ) {
                   List item = (List)i.next();
                   Integer type = (Integer)item.get(0);
                   if (type.intValue() == 2) {
                      String altName = (String)item.get(1);
                   }
                }
             }
 ***/
           }

        }

        /* send missing remoteUser to login */

        if (session.remoteUser==null) {
           if (canLogin) {
              session.mv = loginChooserMV(request, response);
              return session;
           }
           return null;
        }

        session.servletPath = request.getServletPath();
        session.remoteAddr = request.getRemoteAddr();

        // etag headers
        session.ifMatch = getLongHeader(request, "If-Match");
        session.ifNoneMatch = getLongHeader(request, "If-None-Match");
        log.info("tags: match=" + session.ifMatch + ", nonematch=" + session.ifNoneMatch);

        log.info("user: " + session.remoteUser);
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max_age=1");
        response.setHeader("X-UA-Compatible", "IE=7");

        log.info("user: " + session.remoteUser);
        return session;
    }

    private String fixPathName(String start, HttpServletRequest request) {
       String path = request.getPathInfo();
       // log.debug("full path = " + path);
       path = path.substring(start.length());
       // log.debug("trunc path = " + path);
       int slash = path.indexOf("/");
       if (slash>0) path = path.substring(0, slash);
       // log.debug("fixed path = " + path);
       return path;
    }

    /* send user to login page */
    private void sendToLogin(HttpServletRequest request, HttpServletResponse response, String loginPath) {

       // delete any existing sessions first
       Cookie[] cookies = request.getCookies();
       if (cookies!=null) {
         for (int i=0; i<cookies.length; i++) {
           if (cookies[i].getName().startsWith("_shib")) {
              log.debug("clearing cookie " + cookies[i].getName());
              Cookie c = new Cookie(cookies[i].getName(), "");
              c.setSecure(true);
              c.setPath("/");
              c.setMaxAge(0);
              response.addCookie(c);
           }
         }
       }
    
       String rp = "";
       if (request.getPathInfo()!=null) rp = request.getPathInfo();
       String rqs = "";
       if (request.getQueryString()!=null) rqs = "?" +  request.getQueryString();
       String red = browserRootPath + request.getServletPath() + loginPath + rp + rqs;
       log.debug("no user yet: redirect for login to " + red);
       try {
          response.sendRedirect(red);
       } catch (IOException e) {
          log.error("redirect: " + e);
       }
    }

    // create basic model and view
    private ModelAndView basicModelAndView(RPSession session, String view, String basePage) {
        ModelAndView mv = new ModelAndView(view + "/" + basePage);
        mv.addObject("remote_user", session.remoteUser);
        mv.addObject("root", session.rootPath);
        mv.addObject("vers", session.servletPath);
        if (session.pageType != null) mv.addObject("pageType", view + "/" + session.pageType);
        mv.addObject("pageTitle", session.pageTitle);
        mv.addObject("xsrf", session.xsrfCode);
        return mv;
    }
    private ModelAndView basicModelAndView(RPSession session) {
        return (basicModelAndView(session, session.viewType, "page"));
    }
    private ModelAndView basicModelAndView(RPSession session, String view) {
        return (basicModelAndView(session, view, "page"));
    }

    // create 'empty' model and view
    private ModelAndView emptyMV(String message, String alert) {
        ModelAndView mv = new ModelAndView("empty");
        if (message!=null) mv.addObject("msg", message);
        if (message!=null) mv.addObject("alert", alert);
        return mv;
    }
    private ModelAndView emptyMV(String msg) {
        return emptyMV(msg, null);
    }
    private ModelAndView emptyMV() {
        return emptyMV("session error");
    }

    /*
     * Process login page.
     * Set a cookie and redirect back to original request
     * Encode remoteuser, method and time into the login cookie
     *
     * method = 0 -> google
     * method = 1 -> incommon shib
     * method = 2 -> 2-factor uw shib
     */

    private ModelAndView loginPage(HttpServletRequest request, HttpServletResponse response, int method) {
        String remoteUser = request.getRemoteUser();
        if (remoteUser==null && method==0) {  // social login
           String idp = (String)request.getAttribute("Shib-Identity-Provider");
           String mail = (String)request.getAttribute("mail");
           log.info("social login from " + idp + ", email = " + mail);
           if (idp.equals(googleIdentityProvider) && (mail.endsWith(".edu") || mail.endsWith("@gmail.com"))) {
              remoteUser = mail;
           } else {
              log.debug("invalid social login");
              return emptyMV("invalid social login");
           }
        }

       String methodKey = "P";
       if (method==2) methodKey = "2";
       log.debug("method = " + method + ", key = " + methodKey);

       if (remoteUser!=null) {
           if (remoteUser.endsWith("@washington.edu")) {
              remoteUser = remoteUser.substring(0, remoteUser.lastIndexOf("@washington.edu"));
              log.info("dropped @washington.edu to get id = " + remoteUser);
           }
           if (remoteUser.endsWith("@uw.edu")) {
              remoteUser = remoteUser.substring(0, remoteUser.lastIndexOf("@uw.edu"));
              log.info("dropped @uw.edu to get id = " + remoteUser);
           }
           double dbl = Math.random();
           long modtime = new Date().getTime();  // milliseconds
           log.debug("login: ck = ...;" + remoteUser + ";" + dbl + ";" + methodKey + ";" + modtime/1000);
           String enc = RPCrypt.encode(Double.toString(modtime)+ ";" + remoteUser + ";" + dbl + ";" + methodKey + ";" + modtime/1000);
           log.debug("login: enc = " + enc);
           Cookie c = new Cookie(loginCookie, enc);
           c.setSecure(true);
           c.setPath("/");
           response.addCookie(c);
           try {
              String rp = request.getPathInfo();
              int sp = rp.indexOf("/", 2);
              log.debug("in path = " +  rp);
              String red = browserRootPath + request.getServletPath();
              if (sp>1) red = red + rp.substring(sp);
              if (request.getQueryString()!=null)  red = red + "?" + request.getQueryString();
              log.debug("logon ok, return to " + red);
              response.sendRedirect(red);
           } catch (IOException e) {
              log.error("redirect: " + e);
              return emptyMV("redirect error");
           }
       } else {
           // send login failed message
           ModelAndView mv = new ModelAndView("browser/page");
           mv.addObject("root", browserRootPath);
           mv.addObject("vers", request.getServletPath());
           mv.addObject("pageType", "browser/nologin");
           mv.addObject("pageTitle", "login failed");
           mv.addObject("myEntityId", myEntityId);
           return mv;
       }
       return emptyMV();
    }

    @RequestMapping(value="/login/**", method=RequestMethod.GET)
    public ModelAndView basicLoginPage(HttpServletRequest request, HttpServletResponse response) {
        return loginPage(request, response, 1);
    }

    @RequestMapping(value="/securelogin/**", method=RequestMethod.GET)
    public ModelAndView secureLoginPage(HttpServletRequest request, HttpServletResponse response) {
        return loginPage(request, response, 2);
    }

    @RequestMapping(value="/googlelogin/**", method=RequestMethod.GET)
    public ModelAndView googleLoginPage(HttpServletRequest request, HttpServletResponse response) {
        return loginPage(request, response, 0);
    }

    @RequestMapping(value="/incommonlogin/**", method=RequestMethod.GET)
    public ModelAndView incommonLoginPage(HttpServletRequest request, HttpServletResponse response) {
        return loginPage(request, response, 1);
    }

    /*
     * Process logoutt page
     * Clear cookies, redirect to shib logout
     */

    @RequestMapping(value="/logout/**", method=RequestMethod.GET)
    public ModelAndView logoutPage(HttpServletRequest request, HttpServletResponse response) {
        // clear cookies
        Cookie[] cookies = request.getCookies();
        if (cookies!=null) {
          for (int i=0; i<cookies.length; i++) {
            String ckName = cookies[i].getName();
            if (ckName.equals(loginCookie) || ckName.startsWith("_shib")) {
               log.debug("cookie to clear " + ckName);
               Cookie c = new Cookie(ckName, "void");
               c.setSecure(true);
               c.setPath("/");
               c.setMaxAge(0);
               response.addCookie(c);
            }
          }
        }
/**
        try {
           log.debug("redirect to: " +  logoutUrl);
           response.sendRedirect(logoutUrl);
        } catch (IOException e) {
           log.error("redirect: " + e);
        }
        return emptyMV("configuration error");
 **/
       ModelAndView mv = new ModelAndView("browser/chooser");
       mv.addObject("root", browserRootPath);
       mv.addObject("vers", request.getServletPath());
       mv.addObject("pagetype", "browser/loggedout");
       mv.addObject("pathextra", "");
       mv.addObject("uwloginpath", standardLoginPath);
       mv.addObject("googleloginpath", googleLoginPath);
       mv.addObject("incommonloginpath", incommonLoginPath);
       return (mv);
    }

    // show main page
    @RequestMapping(value="/", method=RequestMethod.GET)
    public ModelAndView homePage(HttpServletRequest request, HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response);
        if (session==null) return (emptyMV());
        if (session.mv!=null) return (session.mv);
        log.info("/ view");
        log.info(".. path=" + request.getPathInfo());

        session.pageTitle = "SP registry home";
        session.pageType = "home";

        ModelAndView mv = basicModelAndView(session);

        return (mv);
    }

    // home pages (if 'v1' alone)
    @RequestMapping(value="/v1", method=RequestMethod.GET)
    public ModelAndView homePageV1(HttpServletRequest request, HttpServletResponse response) {
        log.info("v1 view");
        return homePage(request, response);
    }



    // show rp list page
    @RequestMapping(value="/rps", method=RequestMethod.GET)
    public ModelAndView getRelyingParties(@RequestParam(value="selectrp", required=false) String selRp,
            @RequestParam(value="selecttype", required=false) String selType,
            HttpServletRequest request, HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response, false);
        if (session==null) return (emptyMV());
        if (session.mv!=null) return (session.mv);

        List<RelyingParty> relyingParties = null;

        if (selType!=null && selType.equalsIgnoreCase("all")) selType = null;
        relyingParties = rpManager.getRelyingParties(selRp, selType);
        log.info("found " + relyingParties.size() + " rps" );
 
        List<Metadata> metadata = rpManager.getMetadata();
        log.info("found " + metadata.size() + " mds" );
  
        ModelAndView mv = basicModelAndView(session, "json", "rps");
        mv.addObject("selectrp", selRp==null?"":selRp);
        mv.addObject("selecttype", selType==null?"all":selType);
        mv.addObject("relyingParties", relyingParties);
        mv.addObject("metadata", metadata);

        return (mv);
    }

    // specific party
    @RequestMapping(value="/rp", method=RequestMethod.GET)
    public ModelAndView getRelyingParty(@RequestParam(value="id", required=true) String id,
            @RequestParam(value="mdid", required=true) String mdid,
            @RequestParam(value="view", required=false) String view,
            @RequestParam(value="dns", required=false) String dns,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response);
        if (session==null) return (emptyMV());
        if (session.mv!=null) return (session.mv);

        session.pageType = "rp";
        session.pageTitle = "Service provider";

        RelyingParty rp = null;
        RelyingParty rrp = null;

        boolean canEdit = false;
        boolean refreshOpt = false;
        if (view!=null && view.equals("refresh")) refreshOpt = true;

        String errmsg = null;

        ModelAndView mv = basicModelAndView(session, "browser", "rp");

        try {
           rp = rpManager.getRelyingPartyById(id, mdid);
        } catch (RelyingPartyException e) {
           return emptyMV("not found");
        }
        if (refreshOpt) {
           try {
              rrp = rpManager.genRelyingPartyByLookup(dns);
           } catch (RelyingPartyException e) {
              errmsg = "Metadata could not be obtained";
           }
           if (rrp.getEntityId().equals(rp.getEntityId())) {
                rp = rpManager.updateRelyingPartyMD(rp, rrp);
           } else {
              errmsg = "Lookup returned a different entity ID";
           }
        }
        session.pageTitle = rp.getEntityId();
        try {
           if (dnsVerifier.isOwner(id, session.remoteUser, null)) {
              log.debug("user can edit");
              canEdit = true;
           }
        } catch (DNSVerifyException e) {
           mv.addObject("alert", "Could not verify ownership:\n" + e.getCause());
           response.setStatus(500);
           return mv;
        }

        log.info("returning rp id=" + id );
        List<FilterPolicyGroup> filterPolicyGroups = filterPolicyManager.getFilterPolicyGroups();
        List<Attribute> attributes = filterPolicyManager.getAttributes();

        // don't send proxy secret to non-admin
        Proxy googleProxy = proxyManager.getProxy("google", id);
        if (googleProxy!=null && !canEdit) googleProxy.setClientSecret("*****");

        mv.addObject("canEdit", canEdit);
        mv.addObject("relyingParty", rp);
        mv.addObject("filterPolicyGroups", filterPolicyGroups);
        mv.addObject("filterPolicyManager", filterPolicyManager);
        mv.addObject("attributes", attributes);
        mv.addObject("relyingPartyId", id);
        mv.addObject("googleProxy", googleProxy);
        mv.addObject("isAdmin", session.isAdmin);
        return (mv); 
    }

    // new rp
    @RequestMapping(value="/new", method=RequestMethod.GET)
    public ModelAndView getRelyingParty(@RequestParam(value="dns", required=true) String dns,
            @RequestParam(value="mdid", required=false) String mdid,
            @RequestParam(value="view", required=false) String view,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response);
        if (session==null) return (emptyMV());
        if (session.mv!=null) return (session.mv);

        session.pageType = "rp";
        session.pageTitle = "New service provider";
        log.debug("new sp request: " + dns);
        if (dns.length()==0) return (emptyMV());

        RelyingParty rp = null;
        boolean canEdit = true;
        String errmsg = null;
        ModelAndView mv = basicModelAndView(session, "browser", "rp");

        // check access
        try {
           if (dnsVerifier.isOwner(dns, session.remoteUser, null)) {
              log.debug("user owns dns");
           } else {
              mv.addObject("alert", "no permission");
              response.setStatus(401);
              return mv;
           }
        } catch (DNSVerifyException e) {
           mv.addObject("alert", "Could not verify ownership:\n" + e.getCause());
           response.setStatus(500);
           return mv;
        }

        try {
           rp = rpManager.genRelyingPartyByLookup(dns);
        } catch (RelyingPartyException e) {
           return emptyMV("metadata not found");
        }
        session.pageTitle = rp.getEntityId();

        List<FilterPolicyGroup> filterPolicyGroups = filterPolicyManager.getFilterPolicyGroups();
        List<Attribute> attributes = filterPolicyManager.getAttributes();


        mv.addObject("newEntity", true);
        mv.addObject("relyingParty", rp);
        mv.addObject("filterPolicyGroups", filterPolicyGroups);
        mv.addObject("filterPolicyManager", filterPolicyManager);
        mv.addObject("relyingPartyId", rp.getEntityId());
        return (mv); 
    }

    // update an rp metadata
    @RequestMapping(value="/rp", method=RequestMethod.PUT)
    public ModelAndView putRelyingParty(@RequestParam(value="id", required=true) String id,
            @RequestParam(value="mdid", required=true) String mdid,
            @RequestParam(value="xsrf", required=false) String paramXsrf,
            InputStream in,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response, false);
        if (session==null) return (emptyMV());
        log.info("PUT update for: " + id);
        int status = 200;

       if (session.isBrowser && !(paramXsrf!=null && paramXsrf.equals(session.xsrfCode))) {
           log.info("got invalid xsrf=" + paramXsrf + ", expected+" + session.xsrfCode);
           return emptyMV("invalid session (xsrf)");
       }

        ModelAndView mv = emptyMV("OK dokey");

        if (!(id.startsWith("https://")||id.startsWith("http://"))) {
            status = 400;
            mv.addObject("alert", "Not a vaild entity id.");
            response.setStatus(status);
            return mv;
        }
        try {
           if (!dnsVerifier.isOwner(id, session.remoteUser, null)) {
               status = 401;
               mv.addObject("alert", "You are not an owner of that entity.");
               response.setStatus(status);
               return mv;
           }
        } catch (DNSVerifyException e) {
           mv.addObject("alert", "Could not verify ownership:\n" + e.getCause());
           response.setStatus(500);
           return mv;
        }

        Document doc = null;
        try {
            DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = builderFactory.newDocumentBuilder();
            doc = builder.parse (in);
        } catch (Exception e) {
            log.info("parse error: " + e);
            status = 400;
            mv.addObject("alert", "The posted document was not valid:\n" + e);
            response.setStatus(status);
            return mv;
        }
        if (doc!=null) {
           try {
              status = rpManager.updateRelyingParty(doc, mdid);
           } catch (RelyingPartyException e) {
              status = 400;
              mv.addObject("alert", "Update of the entity failed:\n" + e);
           }
        }

        SimpleMailMessage msg = new SimpleMailMessage(this.templateMessage);
        msg.setTo(mailTo);
        String act = "updated";
        if (status==201) act = "created";
        else if (status>=400) act = "attempted edit of";
        msg.setSubject("Service provider metadata " + act + " by " + session.remoteUser);
        msg.setText( "User '" + session.remoteUser + "' " + act + " metadata for '" + id + "'.\nRequest status: " + status +
               "\n\nThis message is advisory.  No response is indicated.");
        try{
            this.mailSender.send(msg);
        } catch(MailException ex) {
            log.error("sending mail: " + ex.getMessage());            
        }

        response.setStatus(status);
        return mv;
    }


    // delete an rp
    @RequestMapping(value="/rp", method=RequestMethod.DELETE)
    public ModelAndView deleteRelyingParty(@RequestParam(value="id", required=true) String id,
            @RequestParam(value="mdid", required=true) String mdid,
            @RequestParam(value="xsrf", required=false) String paramXsrf,
            InputStream in,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response, false);
        if (session==null) return (emptyMV());

        log.info("DELETE for: " + id);
        int status = 200;

       if (session.isBrowser && !(paramXsrf!=null && paramXsrf.equals(session.xsrfCode))) {
           log.info("got invalid xsrf=" + paramXsrf + ", expected+" + session.xsrfCode);
           return emptyMV("invalid session (xsrf)");
       }

        ModelAndView mv = emptyMV("OK dokey delete rp");

        try {
          if (!dnsVerifier.isOwner(id, session.remoteUser, null)) {
               status = 401;
               mv.addObject("alert", "You are not the owner.");
           } else {
              status = rpManager.deleteRelyingParty(id, mdid);
           }
        } catch (DNSVerifyException e) {
           mv.addObject("alert", "Could not verify ownership:\n" + e.getCause());
           response.setStatus(500);
           return mv;
        }
        SimpleMailMessage msg = new SimpleMailMessage(this.templateMessage);
        msg.setTo(mailTo);
        msg.setText( "User '" + session.remoteUser + "' deleted metadata for '" + id + "'.\nRequest status: " + status);
        try{
            this.mailSender.send(msg);
        } catch(MailException ex) {
            log.error("sending mail: " + ex.getMessage());            
        }

        response.setStatus(status);
        return mv;
    }


    // rp attributes 
    @RequestMapping(value="/rp/attr", method=RequestMethod.GET)
    public ModelAndView getRelyingPartyAttributes(@RequestParam(value="id", required=true) String id,
            @RequestParam(value="mdid", required=false) String mdid,
            @RequestParam(value="view", required=false) String view,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response);
        if (session==null) return (emptyMV());
        if (session.mv!=null) return (session.mv);

        session.pageType = "relying-party-attr";
        session.pageTitle = "Service provider";
        if (view==null) view = "";
        else if (view.equals("edit")) session.pageType = "relying-party-attr-edit";

        RelyingParty rp = null;
        String errmsg = null;

        try {
           rp = rpManager.getRelyingPartyById(id, mdid);
        } catch (RelyingPartyException e) {
           return emptyMV("SP not found");
        }
        session.pageTitle = rp.getEntityId();

        List<FilterPolicyGroup> filterPolicyGroups = filterPolicyManager.getFilterPolicyGroups();
        List<Attribute> attributes = filterPolicyManager.getAttributes();

        ModelAndView mv = basicModelAndView(session);

        log.info("returning attrs for id=" + id );

        mv.addObject("relyingPartyId", id);
        mv.addObject("relyingParty", rp);
        mv.addObject("filterPolicyGroups", filterPolicyGroups);
        mv.addObject("filterPolicyManager", filterPolicyManager);
        mv.addObject("remoteUser", session.remoteUser);
        try {
           if (dnsVerifier.isOwner(id, session.remoteUser, null)) mv.addObject("domainOwner",true);
        } catch (DNSVerifyException e) {
           mv.addObject("alert", "Could not verify ownership:\n" + e.getCause());
           response.setStatus(500);
           return mv;
        }
        mv.addObject("attributes", attributes);
        return (mv); 
    }

    // update an rp's attrs
    @RequestMapping(value="/rp/attr", method=RequestMethod.PUT)
    public ModelAndView putRelyingPartyAttributes(@RequestParam(value="id", required=true) String id,
            @RequestParam(value="policyId", required=true) String policyId,
            @RequestParam(value="xsrf", required=false) String paramXsrf,
            InputStream in,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response, false);
        if (session==null) return (emptyMV());
        log.info("PUT update attrs for " + id + " in " + policyId);
        int status = 200;

       if (session.isBrowser && !(paramXsrf!=null && paramXsrf.equals(session.xsrfCode))) {
           log.info("got invalid xsrf=" + paramXsrf + ", expected+" + session.xsrfCode);
           return emptyMV("invalid session (xsrf)");
       }

        ModelAndView mv = emptyMV("OK dokey");

        if (!session.isAdmin) {
            status = 401;
            mv.addObject("alert", "You are not permitted to update attriubtes.");
            response.setStatus(status);
            return mv;
        }

        Document doc = null;
        try {
           DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
           DocumentBuilder builder = builderFactory.newDocumentBuilder();
           doc = builder.parse (in);
        } catch (Exception e) {
           log.info("parse error: " + e);
           status = 400;
           mv.addObject("alert", "The posted document was not valid:\n" + e);
        }
        if (doc!=null) {
           try {
              filterPolicyManager.updateRelyingParty(policyId, doc, session.remoteUser);
              status = 200;
           } catch (FilterPolicyException e) {
              status = 400;
              mv.addObject("alert", "Update of the entity failed:" + e);
           } catch (AttributeNotFoundException e) {
              status = 403;
              mv.addObject("alert", "attribute not found:" + e);
           } catch (NoPermissionException e) {
              status = 401;
              mv.addObject("alert", "no permission" + e);
           }
        }

        SimpleMailMessage msg = new SimpleMailMessage(this.templateMessage);
        msg.setTo(mailTo);
        String act = "updated";
        if (status==201) act = "created";
        msg.setSubject("Service provider attributes " + act + " by " + session.remoteUser);
        msg.setText( "User '" + session.remoteUser + "' " + act + " attributes for '" + id + "'.\nRequest status: " + status + "\n");
        try{
            this.mailSender.send(msg);
        } catch(MailException ex) {
            log.error("sending mail: " + ex.getMessage());            
        }

        response.setStatus(status);
        return mv;
    }


    // request for attributes
    @RequestMapping(value="/rp/attrReq", method=RequestMethod.PUT)
    public ModelAndView putRelyingPartyAttrReq(@RequestParam(value="id", required=true) String id,
            InputStream in,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response, false);
        if (session==null) return (emptyMV());
        log.info("PUT request for: " + id);
        int status = 200;

        ModelAndView mv = emptyMV("OK dokey");

        try {
           if (!dnsVerifier.isOwner(id, session.remoteUser, null)) {
               status = 401;
               mv.addObject("alert", "You are not an owner of that entity.");
           }
        } catch (DNSVerifyException e) {
           mv.addObject("alert", "Could not verify ownership:\n" + e.getCause());
           response.setStatus(500);
           return mv;
        }

        Document doc = null;
        try {
           DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
           DocumentBuilder builder = builderFactory.newDocumentBuilder();
           doc = builder.parse (in);
        } catch (Exception e) {
           log.info("parse error: " + e);
           status = 400;
           mv.addObject("alert", "The posted document was not valid:\n" + e);
        }
        if (doc!=null) {
           List<Element> attrs = XMLHelper.getElementsByName(doc.getDocumentElement(), "Attribute");
           StringBuffer txt = new StringBuffer("User '" + session.remoteUser + "' requests these attributes for '" + id + "'.\n\n");
           for (int i=0; i<attrs.size(); i++) txt.append("   " + attrs.get(i).getAttribute("id") + "\n");
           Element mele = XMLHelper.getElementByName(doc.getDocumentElement(), "Message");
           if (mele!=null) txt.append("\nReason for this:\n\n" + mele.getTextContent() + "\n\n"); 


           SimpleMailMessage msg = new SimpleMailMessage(this.templateMessage);
   /* production to RT system */
           msg.setTo(requestMailTo);
           msg.setSubject("Requesting attributes for " + id);
           msg.setText("//proxy\n//requestor: " + session.remoteUser + "@washington.edu\n\n" + txt.toString());
           try{
               this.mailSender.send(msg);
           } catch(MailException ex) {
               log.error("sending mail: " + ex.getMessage());            
           }

   /** testing
           msg.setTo(session.remoteUser + "@washington.edu");
           msg.setSubject("Requesting attributes for " + id);
           msg.setText("this is the message that would have been sent to RT\n\n" + txt.toString());
           try{
               this.mailSender.send(msg);
           } catch(MailException ex) {
               log.error("sending mail: " + ex.getMessage());            
           }
     **/

        }
        response.setStatus(status);
        return mv;
    }

    // all attributes 
    @RequestMapping(value="/attr", method=RequestMethod.GET)
    public ModelAndView getAttributes(@RequestParam(value="id", required=false) String id,
            @RequestParam(value="mdid", required=false) String mdid,
            @RequestParam(value="view", required=false) String view,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response);
        if (session==null) return (emptyMV());
        if (session.mv!=null) return (session.mv);

        session.pageType = "attributes";
        session.pageTitle = "Attributes";

        // if (view!=null && view.equals("edit")) session.pageType = "relying-party-attr-edit";
        // RelyingParty rp = null;

        List<Attribute> attributes = filterPolicyManager.getAttributes();

        ModelAndView mv = basicModelAndView(session);

        log.info("returning attrs");

        mv.addObject("attributes", attributes);
        mv.addObject("filterPolicyManager", filterPolicyManager);
        mv.addObject("remoteUser", session.remoteUser);
        return (mv); 
    }


    // update an rp's proxy 
    @RequestMapping(value="/rp/proxy", method=RequestMethod.PUT)
    public ModelAndView putRelyingPartyAttributes(@RequestParam(value="id", required=true) String id,
            @RequestParam(value="xsrf", required=false) String paramXsrf,
            InputStream in,
            HttpServletRequest request,
            HttpServletResponse response) {

        RPSession session = processRequestInfo(request, response, false);
        if (session==null) return (emptyMV());
        log.info("PUT update proxy for " + id);
        int status = 200;

       if (session.isBrowser && !(paramXsrf!=null && paramXsrf.equals(session.xsrfCode))) {
           log.info("got invalid xsrf=" + paramXsrf + ", expected+" + session.xsrfCode);
           return emptyMV("invalid session (xsrf)");
       }

        ModelAndView mv = emptyMV("OK dokey");
        try {
           if (!dnsVerifier.isOwner(id, session.remoteUser, null)) {
               status = 401;
               mv.addObject("alert", "You are not an owner of that entity.");
               response.setStatus(status);
               return mv;
           }
        } catch (DNSVerifyException e) {
           mv.addObject("alert", "Could not verify ownership:\n" + e.getCause());
           response.setStatus(500);
           return mv;
        }

        Document doc = null;
        try {
           DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
           DocumentBuilder builder = builderFactory.newDocumentBuilder();
           doc = builder.parse (in);
        } catch (Exception e) {
           log.info("parse error: " + e);
           status = 400;
           mv.addObject("alert", "The posted document was not valid:\n" + e);
        }
        if (doc!=null) {
           try {
              proxyManager.updateProxy(id, doc, session.remoteUser);
              status = 200;
           } catch (ProxyException e) {
              status = 400;
              mv.addObject("alert", "Update of the entity failed:" + e);
           } catch (NoPermissionException e) {
              status = 401;
              mv.addObject("alert", "no permission" + e);
           }
        }

        SimpleMailMessage msg = new SimpleMailMessage(this.templateMessage);
        msg.setTo(mailTo);
        String act = "updated";
        if (status==201) act = "created";
        msg.setSubject("Service provider attributes " + act + " by " + session.remoteUser);
        msg.setText( "User '" + session.remoteUser + "' " + act + " proxy info '" + id + "'.\nRequest status: " + status + "\n");
        try{
            this.mailSender.send(msg);
        } catch(MailException ex) {
            log.error("sending mail: " + ex.getMessage());            
        }

        response.setStatus(status);
        return mv;
    }

    public void setRelyingPartyManager(RelyingPartyManager m) {
        rpManager = m;
    }

    public void setFilterPolicyManager(FilterPolicyManager m) {
        filterPolicyManager = m;
    }

    public void setProxyManager(ProxyManager m) {
        proxyManager = m;
    }


    /* utility */
    

    private long getLongHeader(HttpServletRequest request, String name) {
       try {
          String hdr = request.getHeader(name);
          if (hdr==null) return 0;
          if (hdr.equals("*")) return (-1);
          return Long.parseLong(hdr);
       } catch (NumberFormatException e) {
          return 0;
       }
    }
    private String cleanString(String in) {
       if (in==null) return null;
       return in.replaceAll("&", "").replaceAll("<", "").replaceAll(">", "");
    }
    public void setCertRootPath(String path) {
        certRootPath = path;
    }
    public void setBrowserRootPath(String path) {
        browserRootPath = path;
    }

    public void setLoginCookie(String v) {
        loginCookie = v;
    }

    public void setLogoutUrl(String v) {
        logoutUrl = v;
    }

    public void setCryptKey(String v) {
        cryptKey = v;
    }

    public void setMailTo(String v) {
        mailTo = v;
    }
    public void setRequestMailTo(String v) {
        requestMailTo = v;
    }

    public void setStandardLoginSec(long v) {
        standardLoginSec = v;
    }
    public void setSecureLoginSec(long v) {
        secureLoginSec = v;
    }

    public void setStandardLoginPath(String v) {
        standardLoginPath = v;
    }
    public void setSecureLoginPath(String v) {
        secureLoginPath = v;
    }

    public void setMyEntityId(String v) {
        myEntityId = v;
    }
    public void setEppnName(String v) {
        eppnName = v;
    }



    /* See if extra login suggested.
     */
/**
    private boolean needMoreAuthn(GwsGroup group, GwsSession session, HttpServletResponse response) {
       if (group.getSecurityLevel()>1 && !session.authn2) {
           log.debug("update needs 2-factor");
           if (session.isBrowser) response.setStatus(402);
           else response.setStatus(401);
           return true;
       }
       return false;
    }
 **/

    public void init() {
       log.info("RelyingPartyController init");
       RPCrypt.init(cryptKey);
       adminGroup = groupManager.getGroup(adminGroupName);

    }

    // diagnostic
    @RequestMapping(value="/**", method=RequestMethod.GET)
    public ModelAndView homePageStar(HttpServletRequest request, HttpServletResponse response) {
        log.info("Star view");
        return homePage(request, response);
    }


}
