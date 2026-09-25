import XCTest
import Capacitor
@testable import FacebookLoginPlugin

class FacebookLoginTests: XCTestCase {
    func testNonceIsHashedWithSha256() {
        let plugin = FacebookLoginPlugin()

        XCTAssertEqual(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            plugin.sha256("abc")
        )
    }

    // Stops at the argument check, before the Facebook SDK is used.
    func testLoginWithoutPermissionsIsRejected() {
        let plugin = FacebookLoginPlugin()
        let call = CAPPluginCall(callbackId: "test", methodName: "login", options: [:], success: { _, _ in
            XCTFail("login answers by throwing")
        }, error: { _ in
            XCTFail("login answers by throwing")
        })

        XCTAssertThrowsError(try plugin.login(call)) { error in
            XCTAssertEqual((error as? CAPPluginError)?.message, "Missing permissions argument")
            XCTAssertNil((error as? CAPPluginError)?.code)
        }
    }
}
