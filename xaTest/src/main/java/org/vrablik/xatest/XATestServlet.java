/* --------------------------------------------------------------*\
| Copyright (C) e-Spatial Solutions Limited, All rights reserved. |
\* --------------------------------------------------------------*/
package org.vrablik.xatest;

import org.vrablik.test.database.DbContent;
import org.vrablik.test.database.DbRow;
import org.vrablik.test.database.DbTable;
import org.vrablik.test.database.TestDbOracle;
import org.vrablik.test.infinispan.TestCacheObj;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session content with requests log
 * @author zhenek
 *
 */
public class XATestServlet extends HttpServlet {
    public static final String REQUEST_LOG_NAME = "requestLog";

    /**
     * session key of attribute names
     */
    private static final String CACHE_ATTRIBUTE_NAMES = "CACHE_ATTRIBUTE_NAMES";
    /**
     * another distributed cache to be used independently on session distributed cache
     */
    public static CacheObj cache;

    public static TestDbOracle dbOracle;

    static {
        try {
            cache = CacheObj.createCache("testLBAppCache1");
        } catch (Exception e) {
            e.printStackTrace();
            cache = null;
        }

        try{
            dbOracle = new TestDbOracle();
        } catch (Exception e) {
            e.printStackTrace();
            dbOracle = null;
        }
    }


    public void doGet (HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        PrintWriter out = getOutput(res);
        //get session and create if it doesn't exist
        HttpSession session = req.getSession();


        RequestLog oneLog = this.createRequestLog("GetContent", req);
        updateRequestLog(session, oneLog);

        Enumeration<String> attributeNames = session.getAttributeNames();

        out.print("<html>");
        out.print("<body>");
        out.print("<a href=\"index.jsp\"> Back </a><br/><br/>");

        String metaAttributes = this.getSessionMetaAttributes(req.getSession());
        out.println("<h3>Session meta attributes:</h3>");
        out.println(metaAttributes);
        out.println("<br/>");

        out.println("<h3>Session attributes:</h3>");
        out.println("<br/>");
        while ( attributeNames.hasMoreElements()){
            String key = attributeNames.nextElement();
            if ( !REQUEST_LOG_NAME.equals(key)){
                renderOneItem(out, session.getAttribute(key), key);
            }
        }

        renderOneItem(out, session.getAttribute(REQUEST_LOG_NAME), REQUEST_LOG_NAME);

        // attributes stored in cache created in application
        out.println("<br/>");
        out.println("<h3>Application attributes:</h3>");
        out.println("<br/>");
        Map<String,String> attrCache = cache.getStoredData();
        Set<String> attrNames = (Set<String>)session.getAttribute(CACHE_ATTRIBUTE_NAMES);

        if ( attrNames != null ){
            for (String attrName : attrNames) {
                renderOneItem(out, attrCache.get(attrName), attrName);
            }
        }

        //cache class stored in common classloader (lib directory)
        out.println("<br/>");
        out.println("<h3>Application(jar in lib directory) attributes:</h3>");
        out.println("<br/>");

        if ( attrNames != null ){
            for (String attrName : attrNames) {
                String attrValue = TestCacheObj.cache.get( attrName, false );
                renderOneItem(out, attrValue, attrName);
            }
        }

        try {
            DbContent dbContent = dbOracle.getDbContent();
            List<DbTable> tables = dbContent.getTables();

            for ( DbTable table : tables ){
                out.println("<h3>");
                out.println(table.getTableName() + " , " + table.getColumnNames());
                out.println("</h3>");


                List<DbRow> rows = table.getRows();
                for( DbRow row : rows){
                    out.println("<pre>");
                    out.println(row);
                    out.println("</pre>");
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        out.print("</body>");
        out.print("</html>");

        out.flush();
        out.close();
    }

    /**
     * Session meta attributes
     * @param session
     * @return
     */
    private String getSessionMetaAttributes(HttpSession session) {
        Map<String, String> metaAttributes = new HashMap<String, String>();

        metaAttributes.put( "sessionId", session.getId());
        metaAttributes.put( "cration time", String.valueOf( new Date( session.getCreationTime() ) ) );
        metaAttributes.put( "last accessed time", String.valueOf(  new Date( session.getLastAccessedTime() ) ) );
        metaAttributes.put( "max inactive interval", String.valueOf( session.getMaxInactiveInterval() ) );
        metaAttributes.put( "last access before (miliseconds)", String.valueOf(
                System.currentTimeMillis() - session.getLastAccessedTime() ) );

        StringBuilder s = new StringBuilder(200);

        for (Map.Entry<String, String> item : metaAttributes.entrySet() ) {
            s.append( item.getKey() ).append(" : ").append(item.getValue() ).append("<br/>");
        }

        return s.toString();
    }

    private void renderOneItem(PrintWriter out, Object value,
                               String key) {
        out.print("key: " + key + " value: " + value );
        out.print("<br/>");
    }

    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String action = request.getParameter("action");
        String message = "Action " + action + " finished successfully";

        boolean updateLog = true;
        HttpSession session = request.getSession();

        try{
            if ("setCacheValueOK".equals(action)) {
                boolean throwException = false;
                setCacheValue(request, session, throwException);
            } else if ("setCacheValueERR".equals(action)) {
                boolean throwException = true;
                setCacheValue(request, session, throwException);
            } else {
                message = "Action is not recognized";
            }
        } catch (Exception e){
            e.printStackTrace();
            message = "Action " + action + " failed with message " + e.getMessage();
        }

        if (updateLog) {
            RequestLog oneLog = this.createRequestLog(action, request);
            updateRequestLog(session, oneLog);
        }

        PrintWriter out = null;
        try {
            out = this.getOutput(response);
            out.print("<html>");
            out.print("<body>");
            out.print(message);
            out.print("<br/>");
            out.print("<a href=\"index.jsp\"> Back </a><br/><br/>");
            out.print("</body>");
            out.print("</html>");
            out.flush();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    /**
     * Set cache value to distirbuted cache and add cache name to session CACHE_ATTRIBUTE_NAMES
     * @param request
     * @param session
     * @param throwException
     */
    private void setCacheValue(HttpServletRequest request, HttpSession session, boolean throwException) throws RuntimeException {
        String key = request.getParameter("key");
        String value = request.getParameter("value");

        UserTransaction transaction = null;
        try {
            transaction = this.getTransaction();
        } catch (NamingException e) {
            throw new RuntimeException( e );
        }

        try{
            transaction.begin();

            Set<String> attrNames = (Set<String>)session.getAttribute(CACHE_ATTRIBUTE_NAMES);
            if ( attrNames != null ){
                attrNames = new HashSet<String>( attrNames );
            } else {
                attrNames = new HashSet<String>();
            }


            attrNames.add(key);

            session.setAttribute(CACHE_ATTRIBUTE_NAMES, attrNames);

            //test begin transaction here
            //transaction.begin();

            dbOracle.set(key, value);
            cache.set(key, value);
            TestCacheObj.cache.set(key, "TestCacheObj" + value);

            if ( throwException ){
                throw new RuntimeException("forced throw runtime exception");
            }

            transaction.commit();
            System.out.println("Transaction committed.");
        } catch (Exception e) {
            try {
                transaction.rollback();
            } catch (SystemException e1) {
                System.out.println(e1.getMessage());
            }
            throw new RuntimeException( e );
        }
    }

    private RequestLog createRequestLog(String action, HttpServletRequest request) throws UnknownHostException {
        InetAddress addr = InetAddress.getLocalHost();
        String localName = addr.getHostName();
        String ipAddr = addr.getHostAddress();


        String remoteHost = request.getRemoteHost();
        String remoteAddr = request.getRemoteAddr();

        RequestLog oneLog = new RequestLog(localName, ipAddr, remoteHost, remoteAddr, new Date(), action);

        return oneLog;
    }


    private void updateRequestLog(HttpSession session, RequestLog oneLog) {
        List<RequestLog> logs = (List<RequestLog>)session.getAttribute(REQUEST_LOG_NAME);

        if ( logs == null ){
            logs = new ArrayList<RequestLog>();
            logs.add(oneLog);
        } else {
            //newes log is first
            List<RequestLog>newLogs = new ArrayList<RequestLog>(logs.size()+1);
            newLogs.add(oneLog);
            newLogs.addAll(logs);
            logs = newLogs;
        }

        session.setAttribute(REQUEST_LOG_NAME, logs);
    }


    private PrintWriter getOutput(HttpServletResponse res) throws IOException {
        res.setCharacterEncoding("UTF8");
        res.setContentType("text/html");
        PrintWriter out = res.getWriter();
        return out;
    }

    private UserTransaction getTransaction() throws NamingException {

        InitialContext ctx = null;
        UserTransaction tt;
        try{
            ctx = new InitialContext();
            tt = (UserTransaction) ctx.lookup( "java:comp/UserTransaction" );
        } catch (Exception e ){
            throw new RuntimeException(e);
        } finally {
            if ( ctx != null ){
                ctx.close();
            }
        }

        return tt;
    }
}

