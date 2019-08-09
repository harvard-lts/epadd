package edu.stanford.muse.email.GmailAuth;


/**
 * Created by jaya on 16/01/19.
 * Object returned after verifying the login credentials with any method (PM, Google, FB, etc.)
 */
public class AuthenticatedUserInfo implements java.io.Serializable {

    // for tracking the authenticated information that is put in the session
    private final String idToken; // An ephemeral token assigned by the authentication system, that's validated with the auth system every time a page request is made under login, null for PM login
//    private final AuthMethod authMethod;
    private final String authToken; // A unique token generated by PM for the login session
    private final String authUserId; // The user id that is unique to the auth system such as the google user id, facebook user id
    private final String userName; // A unique username assigned by the authentication system. eg: email in case of google, email is not accessible in facebook, presently same as authToken in pm
    private final String displayName; // eg: <givenName> <FamilyName>
    private final String accessToken;

    public AuthenticatedUserInfo(String idToken, String accessTokenString, /*AuthMethod authMethod,*/ String authToken, String authUserId, String userName, String displayName) {
        this.idToken = idToken;
//        this.authMethod = authMethod;
        this.authToken = authToken;
        this.authUserId = authUserId;
        this.userName = userName;
        this.displayName = displayName;
        this.accessToken = accessTokenString;
    }

    public String getIdToken() {
        return this.idToken;
    }

   /* public AuthMethod getAuthMethod() {
        return this.authMethod;
    }*/

    public String getAuthToken() { return this.authToken; }

    public String getAuthUserId() {
        return this.authUserId;
    }

    public String getUserName() {
        return this.userName;
    }

    public String getAccessToken() {return this.accessToken;}

    public String getDisplayName() {
        return this.displayName;
    }

    public String toString() {
        return /*"authMethod: " + getAuthMethod() +*/
                " displayName: " + getDisplayName() +
                " authToken: " + getAuthToken() +
                " authUserId: " + getAuthUserId() +
                " userName: " + getUserName() +
                " idToken: " + getIdToken();
    }
}
