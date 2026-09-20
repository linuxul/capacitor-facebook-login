import XCTest
@testable import FacebookLoginPlugin

class FacebookLoginTests: XCTestCase {
    func testNonceIsHashedWithSha256() {
        let plugin = FacebookLoginPlugin()

        XCTAssertEqual(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            plugin.sha256("abc")
        )
    }
}
