package controllers.gui.auth;

import java.lang.reflect.InvocationTargetException;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import controllers.SecurityUtil;
import controllers.gui.Application;
import controllers.gui.MyMain;
import controllers.gui.Search;

import play.Play;
import play.data.validation.Required;
import play.libs.Crypto;
import play.libs.Time;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Scope.Session;
import play.utils.Java;

public class GuiSecure extends Controller {

	//for security, we do not use the key "admin" as security won't like that!!!!
	public static String ADMIN_KEY = "age";
	public static String KEY = "key";
	
	private static final Logger log = LoggerFactory.getLogger(GuiSecure.class);
	
    @Before(unless={"login", "authenticate", "logout", "gui.Tables.postData"})
    static void checkAccess() throws Throwable {
        // Authent
        if(!session.contains(KEY)) {
            flash.put("url", "GET".equals(request.method) ? request.url : Play.ctxPath + "/"); // seems a good default
            login();
        }
        if (log.isInfoEnabled())
			log.info("user is already authenticated");
        // Checks
        SecureCheck check = getActionAnnotation(SecureCheck.class);
        if(check != null) {
            check(check);
        }
        check = getControllerInheritedAnnotation(SecureCheck.class);
        if(check != null) {
            check(check);
        }

    	String username = SecurityUtil.getUser();
        controllers.gui.Security.createUserIfNotExist(username);
    }

    private static void check(SecureCheck check) throws Throwable {
        for(String profile : check.value()) {
            boolean hasProfile = (Boolean)Security.invoke("check", profile);
            if(!hasProfile) {
                Security.invoke("onCheckFailed", profile);
            }
        }
    }

    // ~~~ Login
    public static void login() throws Throwable {
    	Http.Cookie remember = null;
    	try {
        remember = request.cookies.get("rememberme");
    	}
    	catch (Throwable t){
    		t.printStackTrace();
    	}
        if(remember != null) {
        	if (log.isInfoEnabled())
    			log.info("remembering user");
            int firstIndex = remember.value.indexOf("-");
            int lastIndex = remember.value.lastIndexOf("-");
            if (lastIndex > firstIndex) {
                String sign = remember.value.substring(0, firstIndex);
                String restOfCookie = remember.value.substring(firstIndex + 1);
                String username = remember.value.substring(firstIndex + 1, lastIndex);
                String time = remember.value.substring(lastIndex + 1);
                Date expirationDate = new Date(Long.parseLong(time)); // surround with try/catch?
                Date now = new Date();
                if (expirationDate == null || expirationDate.before(now)) {
                    logout();
                }
                if(Crypto.sign(restOfCookie).equals(sign)) {
                	SecurityUtil.putUser(username);
                    redirectToOriginalURL();
                }
            }
        }
        
        if (log.isInfoEnabled())
			log.info("hitting login page");
        flash.keep("url");
        
        if(session.get(KEY) != null) {
        	MyMain.dashboard();
        }
        render();
    }

    public static void authenticate(@Required String username, String password, boolean remember) throws Throwable {
    	if (log.isInfoEnabled())
			log.info("trying to login with username="+username);
        // Check tokens
        Boolean allowed = false;
        try {
            // This is the deprecated method name
            allowed = (Boolean)Security.invoke("authentify", username, password);
        } catch (UnsupportedOperationException e ) {
            // This is the official method name
            allowed = (Boolean)Security.invoke("authenticate", username, password);
        }
        if(validation.hasErrors() || !allowed) {
            flash.keep("url");
            flash.error("Invalid username and password combination");
            params.remove("password");
            params.flash();
            login();
        }
        
        Session temp = session;
        
        // Mark user as connected
        SecurityUtil.putUser(username);
        // Remember if needed
        if(remember) {
            Date expiration = new Date();
            String duration = "30d";  // maybe make this override-able 
            expiration.setTime(expiration.getTime() + Time.parseDuration(duration));
            response.setCookie("rememberme", Crypto.sign(username + "-" + expiration.getTime()) + "-" + username + "-" + expiration.getTime(), duration);

        }
        // Redirect to the original URL (or /)
        redirectToOriginalURL();
    }

    public static void logout() throws Throwable {
    	if (log.isInfoEnabled())
			log.info("logging out, redirect to login page");
        Security.invoke("onDisconnect");
        String sid = session.get("sid");
        session.clear();
        session.put("sid", sid); //we only want the sid leftover
        response.removeCookie("rememberme");
        Security.invoke("onDisconnected");
        flash.success("You are now logged out");
        login();
    }

    // ~~~ Utils

    static void redirectToOriginalURL() throws Throwable {
        Security.invoke("onAuthenticated");
        String url = flash.get("url");
        if(url == null) {
            url = "/dashboard";
            
        }
        redirect(url);
    }

    public static class Security extends Controller {

        /**
         * @Deprecated
         * 
         * @param username
         * @param password
         * @return
         */
        static boolean authentify(String username, String password) {
            throw new UnsupportedOperationException();
        }

        /**
         * This method is called during the authentication process. This is where you check if
         * the user is allowed to log in into the system. This is the actual authentication process
         * against a third party system (most of the time a DB).
         *
         * @param username
         * @param password
         * @return true if the authentication process succeeded
         */
        static boolean authenticate(String username, String password) {
            return true;
        }

        /**
         * This method checks that a profile is allowed to view this page/method. This method is called prior
         * to the method's controller annotated with the @Check method. 
         *
         * @param profile
         * @return true if you are allowed to execute this controller method.
         */
        static boolean check(String profile) {
            return true;
        }

        /**
         * This method returns the current connected username
         * @return
         */
        static String connected() {
        	return SecurityUtil.getUser();
        }

        /**
         * Indicate if a user is currently connected
         * @return  true if the user is connected
         */
        static boolean isConnected() {
            return session.contains(KEY);
        }

        /**
         * This method is called after a successful authentication.
         * You need to override this method if you with to perform specific actions (eg. Record the time the user signed in)
         */
        static void onAuthenticated() {
        }

         /**
         * This method is called before a user tries to sign off.
         * You need to override this method if you wish to perform specific actions (eg. Record the name of the user who signed off)
         */
        static void onDisconnect() {
        }

         /**
         * This method is called after a successful sign off.
         * You need to override this method if you wish to perform specific actions (eg. Record the time the user signed off)
         */
        static void onDisconnected() {
        }

        /**
         * This method is called if a check does not succeed. By default it shows the not allowed page (the controller forbidden method).
         * @param profile
         */
        static void onCheckFailed(String profile) {
            forbidden();
        }

        private static Object invoke(String m, Object... args) throws Throwable {

            try {
                return Java.invokeChildOrStatic(Security.class, m, args);       
            } catch(InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

    }

}

