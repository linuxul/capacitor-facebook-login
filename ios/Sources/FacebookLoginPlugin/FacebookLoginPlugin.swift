import Foundation
import Capacitor
import FBSDKLoginKit
import CryptoKit

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(FacebookLoginPlugin)
public class FacebookLoginPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FacebookLoginPlugin"
    public let jsName = "FacebookLogin"
    public let pluginMethods: [CAPPluginMethod] = [
        .promise("initialize", FacebookLoginPlugin.initialize),
        .promise("login", FacebookLoginPlugin.login),
        .promise("logout", FacebookLoginPlugin.logout),
        .promise("getCurrentAccessToken", FacebookLoginPlugin.getCurrentAccessToken),
        .promise("getProfile", FacebookLoginPlugin.getProfile),
        .promise("reauthorize", FacebookLoginPlugin.reauthorize),
        .promise("logEvent", FacebookLoginPlugin.logEvent),
        .promise("setAutoLogAppEventsEnabled", FacebookLoginPlugin.setAutoLogAppEventsEnabled),
        .promise("setAdvertiserTrackingEnabled", FacebookLoginPlugin.setAdvertiserTrackingEnabled),
        .promise("setAdvertiserIDCollectionEnabled", FacebookLoginPlugin.setAdvertiserIDCollectionEnabled)
    ]

    // The methods stay synchronous. login and reauthorize hand the Facebook login UI to the main queue and settle
    // from LoginManager's completion handler, which the SDK drops without calling when a login is already running,
    // so they are not awaited through a continuation. The others are ordered setters or settle from SDK callbacks.

    private let loginManager = LoginManager()
    private let dateFormatter = ISO8601DateFormatter()

    override public func load() {
        dateFormatter.formatOptions = [.withInternetDateTime]
    }

    private func dateToJS(_ date: Date) -> String {
        return dateFormatter.string(from: date)
    }

    func initialize(_ call: CAPPluginCall) {
        call.resolve()
    }

    func login(_ call: CAPPluginCall) throws {
        guard let permissions = call.getArray("permissions", String.self) else {
            throw CAPPluginError("Missing permissions argument")
        }

        let nonce = call.getString("nonce") ?? ""
        let tracking = call.getString("tracking") ?? "limited"

        var configuration: LoginConfiguration?

        if nonce.isEmpty {
            configuration = LoginConfiguration(
                permissions: permissions,
                tracking: tracking == "limited" ? .limited : .enabled
            )
        } else {
            configuration = LoginConfiguration(
                permissions: permissions,
                tracking: tracking == "limited" ? .limited : .enabled,
                nonce: self.sha256(nonce)
            )
        }

        guard let _ = configuration else {
            // エラー処理
            return
        }

        DispatchQueue.main.async {
            self.loginManager.logIn(configuration: configuration) { result in
                switch result {
                case .cancelled:
                    print("User cancelled login")
                    call.resolve()
                case .failed:
                    call.reject("LoginManager.logIn failed")
                case .success:
                    print("Logged in")
                    return self.getCurrentAccessToken(call)
                }
            }
        }
    }

    func sha256(_ input: String) -> String {
        let inputData = Data(input.utf8)
        let hashed = SHA256.hash(data: inputData)
        return hashed.compactMap { String(format: "%02x", $0) }.joined()
    }

    func logout(_ call: CAPPluginCall) {
        loginManager.logOut()
        call.resolve()
    }

    func reauthorize(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let token = AccessToken.current, !token.isDataAccessExpired {
                return self.getCurrentAccessToken(call)
            } else {
                self.loginManager.reauthorizeDataAccess(from: (self.bridge?.viewController)!) { (loginResult, error) in
                    if (loginResult?.token) != nil {
                        return self.getCurrentAccessToken(call)
                    } else {
                        print(error!)
                        call.reject("LoginManager.reauthorize failed")
                    }
                }
            }
        }
    }

    func getCurrentAccessToken(_ call: CAPPluginCall) {
        guard let authenticationToken = AuthenticationToken.current else {
            call.resolve()
            return
        }

        guard let userProfile = Profile.current else {
            call.resolve()
            return
        }

        call.resolve([ "accessToken": [
            "token": authenticationToken.tokenString,
            "userId": userProfile.userID,
            "name": userProfile.name,
            "email": userProfile.email
        ]
        ])
    }

    func getProfile(_ call: CAPPluginCall) throws {
        guard let accessToken = AccessToken.current else {
            throw CAPPluginError("You're not logged in. Call FacebookLogin.login() first to obtain an access token.")
        }

        if accessToken.isExpired {
            throw CAPPluginError("AccessToken is expired.")
        }

        guard let fields = call.getArray("fields", String.self) else {
            throw CAPPluginError("Missing fields argument")
        }
        let parameters = ["fields": fields.joined(separator: ",")]
        let graphRequest = GraphRequest.init(graphPath: "me", parameters: parameters)

        graphRequest.start { (_ connection, _ result, _ error) in
            if error != nil {
                call.reject("An error has been occured.")
                return
            }

            call.resolve(result as! [String: Any])
        }
    }

    func logEvent(_ call: CAPPluginCall) {
        if let eventName = call.getString("eventName") {
            let parameters = call.getObject("parameters")?.reduce(into: [AppEvents.ParameterName: Any]()) { result, item in
                if item.value is String || item.value is NSNumber {
                    result[AppEvents.ParameterName(item.key)] = item.value
                }
            }
            AppEvents.shared.logEvent(AppEvents.Name(eventName), parameters: parameters ?? [:])
        }

        call.resolve()
    }

    func setAutoLogAppEventsEnabled(_ call: CAPPluginCall) {
        if let enabled = call.getBool("enabled") {
            Settings.shared.isAutoLogAppEventsEnabled = enabled
        } else {
            Settings.shared.isAutoLogAppEventsEnabled = false
        }
        call.resolve()
    }

    func setAdvertiserTrackingEnabled(_ call: CAPPluginCall) {
        Settings.shared.isAdvertiserTrackingEnabled = call.getBool("enabled", false)
        call.resolve()
    }

    func setAdvertiserIDCollectionEnabled(_ call: CAPPluginCall) {
        if let enabled = call.getBool("enabled") {
            Settings.shared.isAdvertiserIDCollectionEnabled = enabled
        } else {
            Settings.shared.isAdvertiserIDCollectionEnabled = false
        }
        call.resolve()
    }
}
