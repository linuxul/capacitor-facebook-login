// swift-tools-version: 5.9
import Foundation
import PackageDescription

// Apps override this dependency with the @capacitor/ios they installed. To build this package on its own
// against a local runtime, point CAPACITOR_IOS_PATH at it.
let capacitor: Package.Dependency
if let path = ProcessInfo.processInfo.environment["CAPACITOR_IOS_PATH"] {
    capacitor = .package(name: "capacitor-swift-pm", path: path)
} else {
    capacitor = .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
}

let package = Package(
    name: "CapacitorCommunityFacebookLogin",
    platforms: [.iOS(.v17)],
    products: [
        .library(
            name: "CapacitorCommunityFacebookLogin",
            targets: ["FacebookLoginPlugin"])
    ],
    dependencies: [
        capacitor,
        .package(url: "https://github.com/facebook/facebook-ios-sdk.git", .upToNextMajor(from: "18.1.0"))
    ],
    targets: [
        .target(
            name: "FacebookLoginPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "FacebookCore", package: "facebook-ios-sdk"),
                .product(name: "FacebookLogin", package: "facebook-ios-sdk")
            ],
            path: "ios/Sources/FacebookLoginPlugin"),
        .testTarget(
            name: "FacebookLoginPluginTests",
            dependencies: ["FacebookLoginPlugin"],
            path: "ios/Tests/FacebookLoginPluginTests")
    ]
)
