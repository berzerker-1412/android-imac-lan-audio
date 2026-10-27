import AVFoundation
import AppKit
import CoreAudio
import CoreFoundation
import CoreImage
import CoreImage.CIFilterBuiltins
import Darwin
import dnssd
import Foundation
import Security

private let audioSampleRate = 48_000.0
private let audioChannels = 2
private let listenPort: UInt16 = 50_555
private let controlPort: UInt16 = 50_556
private let ringCapacityFrames = 12_000 // 250 ms at 48 kHz
private let serviceType = "_openbot-lanaudio._tcp."
private let servicePort: UInt16 = listenPort

struct ReceiverStatus: Codable {
    let pid: Int32
    let state: String
    let ip: String
    let port: UInt16
    let pin: String
    let service: String
    let updatedAt: Date
}

func statusFileURL() -> URL {
    FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/OpenBot/LAN Audio Receiver", isDirectory: true)
        .appendingPathComponent("status.json")
}

private func privateDirectoryURL() -> URL { statusFileURL().deletingLastPathComponent() }
private func qrFileURL() -> URL { privateDirectoryURL().appendingPathComponent("pairing-qr.png") }

func localReceiverName() -> String {
    let hostName = Host.current().localizedName?.trimmingCharacters(in: .whitespacesAndNewlines)
    let base = (hostName?.isEmpty == false ? hostName! : "Mac")
    return "\(base) LAN Audio"
}

private func removePrivateQR() {
    try? FileManager.default.removeItem(at: qrFileURL())
}

private func writePrivateStatus(ip: String, pin: String, state: String) throws {
    let url = statusFileURL()
    let directory = url.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    _ = chmod(directory.path, mode_t(S_IRWXU))
    let value = ReceiverStatus(pid: getpid(), state: state, ip: ip, port: listenPort,
                               pin: pin, service: serviceType, updatedAt: Date())
    let data = try JSONEncoder().encode(value)
    let temporary = directory.appendingPathComponent("status.\(getpid()).tmp")
    try data.write(to: temporary, options: .atomic)
    _ = chmod(temporary.path, mode_t(S_IRUSR | S_IWUSR))
    if rename(temporary.path, url.path) != 0 {
        let code = errno
        try? FileManager.default.removeItem(at: temporary)
        throw ReceiverError.system("write private status", code)
    }
    _ = chmod(url.path, mode_t(S_IRUSR | S_IWUSR))
}

private func readStatusCommand() -> Int32 {
    let url = statusFileURL()
    guard let data = try? Data(contentsOf: url),
          let value = try? JSONDecoder().decode(ReceiverStatus.self, from: data),
          kill(value.pid, 0) == 0 else {
        print("Receiver is not running or status is unavailable.")
        return 1
    }
    print("Status: \(value.state)")
    print("Address: \(value.ip):\(value.port)")
    print("Service: \(value.service)")
    if isatty(STDOUT_FILENO) == 1 {
        print("Pairing PIN: \(value.pin)")
    } else {
        print("Pairing PIN: hidden (run this command in Terminal)")
    }
    return 0
}

private func createQRPNG(uri: String, at url: URL) throws {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(uri.utf8)
    filter.correctionLevel = "H"
    guard let qrImage = filter.outputImage else {
        throw ReceiverError.audio("Could not generate QR image")
    }
    let scaled = qrImage.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
    let context = CIContext(options: [.useSoftwareRenderer: true])
    guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else {
        throw ReceiverError.audio("Could not render QR image")
    }
    let bitmap = NSBitmapImageRep(cgImage: cgImage)
    guard let png = bitmap.representation(using: .png, properties: [:]) else {
        throw ReceiverError.audio("Could not encode QR PNG")
    }
    let directory = url.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    _ = chmod(directory.path, mode_t(S_IRWXU))
    let temporary = directory.appendingPathComponent("pairing-qr.\(getpid()).tmp")
    try png.write(to: temporary, options: .atomic)
    _ = chmod(temporary.path, mode_t(S_IRUSR | S_IWUSR))
    if rename(temporary.path, url.path) != 0 {
        let code = errno
        try? FileManager.default.removeItem(at: temporary)
        throw ReceiverError.system("write private QR image", code)
    }
    _ = chmod(url.path, mode_t(S_IRUSR | S_IWUSR))
}

func pairingURI(ip: String, pin: String, name: String) -> String {
    var components = URLComponents()
    components.scheme = "openbot-lanaudio"
    components.host = "pair"
    components.queryItems = [
        URLQueryItem(name: "v", value: "1"),
        URLQueryItem(name: "host", value: ip),
        URLQueryItem(name: "port", value: String(listenPort)),
        URLQueryItem(name: "pin", value: pin),
        URLQueryItem(name: "name", value: name)
    ]
    return components.string ?? ""
}

private func showQRCommand(testOnly: Bool = false) -> Int32 {
    let ip: String
    let pin: String
    let name: String
    let targetURL: URL
    if testOnly {
        ip = "192.0.2.1"
        pin = "000000"
        name = "Test Mac LAN Audio"
        targetURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("openbot-lanaudio-qr-test-\(getpid()).png")
    } else {
        let url = statusFileURL()
        guard let data = try? Data(contentsOf: url),
              let value = try? JSONDecoder().decode(ReceiverStatus.self, from: data),
              value.state == "ready", kill(value.pid, 0) == 0 else {
            fputs("Receiver is not ready; start it before showing the pairing QR.\n", stderr)
            return 1
        }
        ip = value.ip
        pin = value.pin
        name = localReceiverName()
        targetURL = qrFileURL()
    }
    do {
        try createQRPNG(uri: pairingURI(ip: ip, pin: pin, name: name), at: targetURL)
        if testOnly {
            guard let image = NSImage(contentsOf: targetURL), image.size.width > 0 else {
                try? FileManager.default.removeItem(at: targetURL)
                fputs("QR image verification failed.\n", stderr)
                return 1
            }
            try FileManager.default.removeItem(at: targetURL)
            print("QR generation test: PASS (PNG generated and decoded; private test data removed)")
            return 0
        }
        guard NSWorkspace.shared.open(targetURL) else {
            print("QR PNG saved privately at \(targetURL.path); open it on the iMac to scan.")
            return 1
        }
        print("Pairing QR opened on this Mac; file is private and removed when the receiver stops.")
        return 0
    } catch {
        fputs("QR generation failed: \(error)\n", stderr)
        return 1
    }
}

private func removeQRCommand() -> Int32 {
    do {
        try FileManager.default.removeItem(at: qrFileURL())
        print("Private pairing QR removed.")
        return 0
    } catch CocoaError.fileNoSuchFile {
        print("No private pairing QR exists.")
        return 0
    } catch {
        fputs("Could not remove private pairing QR: \(error)\n", stderr)
        return 1
    }
}

private func testSystemVolumeCommand() -> Int32 {
    do {
        let initial = try SystemVolume.getPercent()
        let final = try SystemVolume.setPercent(initial)
        guard (0...100).contains(initial), (0...100).contains(final) else {
            fputs("CoreAudio volume verification returned an invalid value.\n", stderr)
            return 1
        }
        print("CoreAudio get/set test: PASS (set to the current rounded system volume)")
        return 0
    } catch {
        fputs("CoreAudio volume test unavailable: \(error)\n", stderr)
        return 1
    }
}

private func testPairingStoreCommand() -> Int32 {
    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("OpenBotPairingTest-\(UUID().uuidString)", isDirectory: true)
    let url = directory.appendingPathComponent("paired-devices.json")
    defer { try? FileManager.default.removeItem(at: directory) }
    let id = "0123456789abcdef0123456789abcdef"
    let token = String(repeating: "a", count: 64)
    let first = PairingStore(url: url)
    guard first.register(id: id, name: "Test Android", token: token),
          first.isAuthorized(id: id, token: token),
          let fileAttributes = try? FileManager.default.attributesOfItem(atPath: url.path),
          let fileMode = fileAttributes[.posixPermissions] as? NSNumber,
          fileMode.intValue == 0o600,
          let directoryAttributes = try? FileManager.default.attributesOfItem(atPath: directory.path),
          let directoryMode = directoryAttributes[.posixPermissions] as? NSNumber,
          directoryMode.intValue == 0o700 else {
        fputs("Pairing store create/permissions test failed.\n", stderr)
        return 1
    }
    let afterRestart = PairingStore(url: url)
    guard afterRestart.isAuthorized(id: id, token: token),
          afterRestart.revoke(id: id, token: token),
          !PairingStore(url: url).isAuthorized(id: id, token: token) else {
        fputs("Pairing store persistence/revocation test failed.\n", stderr)
        return 1
    }
    print("Pairing store: PASS (restart persistence, atomic owner-only file, revoke; test credential removed)")
    return 0
}

private final class BonjourPublisher {
    private final class CallbackBox {
        let ready: () -> Void
        let failure: (String) -> Void
        init(ready: @escaping () -> Void, failure: @escaping (String) -> Void) {
            self.ready = ready
            self.failure = failure
        }
    }
    private var serviceRef: DNSServiceRef?
    private var readSource: DispatchSourceRead?
    private var callbackBox: CallbackBox?

    init(name: String, onReady: @escaping () -> Void, onFailure: @escaping (String) -> Void) throws {
        let box = CallbackBox(ready: onReady, failure: onFailure)
        callbackBox = box
        var ref: DNSServiceRef?
        let txt: [UInt8] = [3, 0x76, 0x3d, 0x31] // v=1
        let result = name.withCString { namePointer in
            serviceType.withCString { typePointer in
                "local.".withCString { domainPointer in
                    txt.withUnsafeBufferPointer { txtBuffer in
                        DNSServiceRegister(&ref, 0, UInt32(kDNSServiceInterfaceIndexAny), namePointer,
                                           typePointer, domainPointer, nil, servicePort.bigEndian,
                                           UInt16(txtBuffer.count), txtBuffer.baseAddress,
                                           { _, _, error, _, _, _, context in
                                               guard let context else { return }
                                               let callbackBox = Unmanaged<CallbackBox>.fromOpaque(context).takeUnretainedValue()
                                               if error == kDNSServiceErr_NoError {
                                                   callbackBox.ready()
                                               } else {
                                                   callbackBox.failure("Bonjour registration failed (\(error))")
                                               }
                                           }, Unmanaged.passUnretained(box).toOpaque())
                    }
                }
            }
        }
        guard result == kDNSServiceErr_NoError, let ref else {
            throw ReceiverError.audio("Bonjour registration failed (\(result))")
        }
        serviceRef = ref
        let fd = DNSServiceRefSockFD(ref)
        guard fd >= 0 else { throw ReceiverError.audio("Bonjour socket unavailable") }
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: .global())
        source.setEventHandler { [weak self] in self?.processResult() }
        source.resume()
        readSource = source
        // The registration callback is delivered by processResult; discovery is not advertised
        // until the listener and audio engine are already ready.
    }

    private func processResult() {
        guard let serviceRef else { return }
        let result = DNSServiceProcessResult(serviceRef)
        if result != kDNSServiceErr_NoError {
            callbackBox?.failure("Bonjour processing failed (\(result))")
        }
    }

    func stop() {
        readSource?.cancel()
        readSource = nil
        if let serviceRef { DNSServiceRefDeallocate(serviceRef) }
        serviceRef = nil
    }
}

private func status(_ message: String) {
    print(message)
    fflush(stdout)
    desktopHostCondition.lock()
    let handler = desktopStatusHandler
    desktopHostCondition.unlock()
    handler?(message)
}

private final class StopState {
    private let lock = NSLock()
    private var value = false

    func requestStop() {
        lock.lock()
        value = true
        lock.unlock()
    }

    var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return value
    }
}

private let desktopHostCondition = NSCondition()
private var desktopHostActive = false
private var desktopHostStopState: StopState?
private var desktopStatusHandler: ((String) -> Void)?
private var desktopConnected = false

func setDesktopStatusHandler(_ handler: ((String) -> Void)?) {
    desktopHostCondition.lock()
    desktopStatusHandler = handler
    desktopHostCondition.unlock()
}

func desktopReceiverIsRunning() -> Bool {
    desktopHostCondition.lock()
    defer { desktopHostCondition.unlock() }
    return desktopHostActive
}

func desktopSenderIsConnected() -> Bool {
    desktopHostCondition.lock()
    defer { desktopHostCondition.unlock() }
    return desktopConnected
}

private func setDesktopConnected(_ connected: Bool) {
    desktopHostCondition.lock()
    desktopConnected = connected
    desktopHostCondition.unlock()
}

@discardableResult
func startDesktopReceiver(onFailure: @escaping (String) -> Void) -> Bool {
    desktopHostCondition.lock()
    guard !desktopHostActive else {
        desktopHostCondition.unlock()
        return false
    }
    desktopHostActive = true
    let stopState = StopState()
    desktopHostStopState = stopState
    desktopHostCondition.unlock()

    DispatchQueue.global(qos: .userInitiated).async {
        do {
            try runReceiver(stopState: stopState)
        } catch {
            onFailure(String(describing: error))
        }
        desktopHostCondition.lock()
        desktopHostActive = false
        desktopHostStopState = nil
        desktopHostCondition.broadcast()
        desktopHostCondition.unlock()
    }
    return true
}

func stopDesktopReceiverAndWait(timeout: TimeInterval = 3.0) {
    desktopHostCondition.lock()
    desktopHostStopState?.requestStop()
    let deadline = Date().addingTimeInterval(timeout)
    while desktopHostActive && desktopHostCondition.wait(until: deadline) { }
    desktopHostCondition.unlock()
}

func currentDesktopReceiverStatus() -> ReceiverStatus? {
    guard let data = try? Data(contentsOf: statusFileURL()) else { return nil }
    return try? JSONDecoder().decode(ReceiverStatus.self, from: data)
}

func pairingQRImageForCurrentReceiver() throws -> NSImage {
    guard let value = currentDesktopReceiverStatus(), value.state == "ready", kill(value.pid, 0) == 0 else {
        throw NSError(domain: "OpenBotLANAudio", code: 1,
                      userInfo: [NSLocalizedDescriptionKey: "Start the receiver before opening pairing."])
    }
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(pairingURI(ip: value.ip, pin: value.pin, name: localReceiverName()).utf8)
    filter.correctionLevel = "H"
    guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
          let cgImage = CIContext(options: [.useSoftwareRenderer: true]).createCGImage(output, from: output.extent) else {
        throw NSError(domain: "OpenBotLANAudio", code: 2,
                      userInfo: [NSLocalizedDescriptionKey: "Could not render the pairing QR."])
    }
    return NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
}

func currentSystemVolumePercent() -> Int? { try? SystemVolume.getPercent() }

func currentSystemOutputName() -> String {
    do {
        let device = try SystemVolume.defaultOutputDevice()
        var address = AudioObjectPropertyAddress(mSelector: kAudioObjectPropertyName,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var name: CFString?
        var size = UInt32(MemoryLayout<CFString?>.size)
        let result = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &name)
        return result == noErr ? (name as String? ?? "Default output device") : "Default output device"
    } catch {
        return "Default output device unavailable"
    }
}

/// Single network writer and one AVAudioEngine render reader. Critical sections
/// are bounded to at most one 4 KiB network read (1024 audio frames).
private final class StereoRingBuffer {
    private let lock = NSLock()
    private let capacity: Int
    private var left: [Float]
    private var right: [Float]
    private var readIndex = 0
    private var writeIndex = 0
    private var queuedFrames = 0
    private var droppedFrames: UInt64 = 0
    private var renderedFrames: UInt64 = 0
    private var callbackCount: UInt64 = 0
    private var lastBufferCount = 0
    private var lastBufferChannels: UInt32 = 0

    init(capacityFrames: Int) {
        capacity = capacityFrames
        left = Array(repeating: 0, count: capacityFrames)
        right = Array(repeating: 0, count: capacityFrames)
    }

    func appendPCM16LE(_ bytes: [UInt8], frameCount: Int) {
        guard frameCount > 0 else { return }
        lock.lock()
        for frame in 0..<frameCount {
            let offset = frame * 4
            let lBits = UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
            let rBits = UInt16(bytes[offset + 2]) | (UInt16(bytes[offset + 3]) << 8)
            let lSample = Int16(bitPattern: lBits)
            let rSample = Int16(bitPattern: rBits)

            if queuedFrames == capacity {
                readIndex = (readIndex + 1) % capacity
                queuedFrames -= 1
                droppedFrames &+= 1
            }
            left[writeIndex] = Float(lSample) / 32768.0
            right[writeIndex] = Float(rSample) / 32768.0
            writeIndex = (writeIndex + 1) % capacity
            queuedFrames += 1
        }
        lock.unlock()
    }

    func render(left outputLeft: UnsafeMutablePointer<Float>,
                right outputRight: UnsafeMutablePointer<Float>,
                frameCount: Int) {
        lock.lock()
        let copied = min(frameCount, queuedFrames)
        for frame in 0..<copied {
            outputLeft[frame] = left[readIndex]
            outputRight[frame] = right[readIndex]
            readIndex = (readIndex + 1) % capacity
        }
        queuedFrames -= copied
        renderedFrames &+= UInt64(copied)
        lock.unlock()

        if copied < frameCount {
            for frame in copied..<frameCount {
                outputLeft[frame] = 0
                outputRight[frame] = 0
            }
        }
    }

    func render(interleaved output: UnsafeMutablePointer<Float>, frameCount: Int) {
        lock.lock()
        let copied = min(frameCount, queuedFrames)
        for frame in 0..<copied {
            output[frame * 2] = left[readIndex]
            output[frame * 2 + 1] = right[readIndex]
            readIndex = (readIndex + 1) % capacity
        }
        queuedFrames -= copied
        renderedFrames &+= UInt64(copied)
        lock.unlock()

        if copied < frameCount {
            for frame in copied..<frameCount {
                output[frame * 2] = 0
                output[frame * 2 + 1] = 0
            }
        }
    }

    func noteCallback(bufferCount: Int, firstBufferChannels: UInt32) {
        lock.lock()
        callbackCount &+= 1
        lastBufferCount = bufferCount
        lastBufferChannels = firstBufferChannels
        lock.unlock()
    }

    func clear() {
        lock.lock()
        readIndex = 0
        writeIndex = 0
        queuedFrames = 0
        lock.unlock()
    }

    var dropped: UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return droppedFrames
    }

    var rendered: UInt64 {
        lock.lock()
        defer { lock.unlock() }
        return renderedFrames
    }

    var queued: Int {
        lock.lock()
        defer { lock.unlock() }
        return queuedFrames
    }

    var callbackDiagnostics: String {
        lock.lock()
        defer { lock.unlock() }
        return "callbacks: \(callbackCount), buffers: \(lastBufferCount), first buffer channels: \(lastBufferChannels)"
    }
}

private final class AudioOutput {
    private let engine = AVAudioEngine()
    private let ring = StereoRingBuffer(capacityFrames: ringCapacityFrames)

    init() throws {
        guard let audioFormat = AVAudioFormat(standardFormatWithSampleRate: audioSampleRate,
                                             channels: AVAudioChannelCount(audioChannels)) else {
            throw ReceiverError.audio("Could not create 48 kHz stereo audio format")
        }
        let sharedRing = ring
        let source = AVAudioSourceNode(format: audioFormat) { _, _, frameCount, audioBufferList in
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let firstChannels = buffers.first?.mNumberChannels ?? 0
            sharedRing.noteCallback(bufferCount: buffers.count, firstBufferChannels: firstChannels)
            if buffers.count == 1, firstChannels >= 2, let data = buffers[0].mData {
                sharedRing.render(interleaved: data.assumingMemoryBound(to: Float.self),
                                  frameCount: Int(frameCount))
                return noErr
            }
            guard buffers.count >= 2,
                  let leftData = buffers[0].mData,
                  let rightData = buffers[1].mData else {
                return kAudio_ParamError
            }
            sharedRing.render(left: leftData.assumingMemoryBound(to: Float.self),
                              right: rightData.assumingMemoryBound(to: Float.self),
                              frameCount: Int(frameCount))
            return noErr
        }
        engine.attach(source)
        engine.connect(source, to: engine.outputNode, format: audioFormat)
        try engine.start()
        let systemOutput = engine.outputNode.outputFormat(forBus: 0)
        status("System audio output: \(Int(systemOutput.sampleRate)) Hz, \(systemOutput.channelCount) channels")
    }

    func stopEngine() {
        ring.clear()
        engine.stop()
    }

    func enqueue(_ bytes: [UInt8], frameCount: Int) {
        ring.appendPCM16LE(bytes, frameCount: frameCount)
    }

    var droppedFrames: UInt64 { ring.dropped }
    var renderedFrames: UInt64 { ring.rendered }
    var callbackDiagnostics: String { ring.callbackDiagnostics }

    func drainCurrentStream(timeout: TimeInterval) {
        let deadline = Date().addingTimeInterval(timeout)
        while ring.queued > 0 && Date() < deadline {
            Thread.sleep(forTimeInterval: 0.005)
        }
        ring.clear()
    }
}

private enum ReceiverError: Error, CustomStringConvertible {
    case system(String, Int32)
    case audio(String)
    case random(String)

    var description: String {
        switch self {
        case let .system(operation, code): return "\(operation) failed (errno \(code))"
        case let .audio(message): return message
        case let .random(message): return message
        }
    }
}

private enum SystemVolume {
    static func defaultOutputDevice() throws -> AudioDeviceID {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain)
        var device = AudioDeviceID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        let result = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject),
                                                &address, 0, nil, &size, &device)
        guard result == noErr, device != AudioDeviceID(kAudioObjectUnknown) else {
            throw ReceiverError.audio("No default output device is available")
        }
        return device
    }

    private static func volumeAddresses() -> [AudioObjectPropertyAddress] {
        [
            AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyVolumeScalar,
                                       mScope: kAudioDevicePropertyScopeOutput,
                                       mElement: kAudioObjectPropertyElementMain),
            AudioObjectPropertyAddress(mSelector: kAudioHardwareServiceDeviceProperty_VirtualMainVolume,
                                       mScope: kAudioDevicePropertyScopeOutput,
                                       mElement: kAudioObjectPropertyElementMain)
        ]
    }

    static func getPercent() throws -> Int {
        let device = try defaultOutputDevice()
        for originalAddress in volumeAddresses() {
            var address = originalAddress
            guard AudioObjectHasProperty(device, &address) else { continue }
            var volume: Float32 = 0
            var size = UInt32(MemoryLayout<Float32>.size)
            let result = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &volume)
            if result == noErr, volume.isFinite, (0.0...1.0).contains(volume) {
                return Int((volume * 100.0).rounded())
            }
        }
        throw ReceiverError.audio("Default output does not expose readable system volume")
    }

    static func setPercent(_ percent: Int) throws -> Int {
        let device = try defaultOutputDevice()
        var lastStatus: OSStatus = noErr
        for originalAddress in volumeAddresses() {
            var address = originalAddress
            guard AudioObjectHasProperty(device, &address) else { continue }
            var settable: DarwinBoolean = false
            let propertyResult = AudioObjectIsPropertySettable(device, &address, &settable)
            guard propertyResult == noErr, settable.boolValue else { continue }
            var volume = Float32(percent) / 100.0
            lastStatus = AudioObjectSetPropertyData(device, &address, 0, nil,
                                                    UInt32(MemoryLayout<Float32>.size), &volume)
            if lastStatus == noErr { return try getPercent() }
        }
        if lastStatus != noErr {
            throw ReceiverError.audio("macOS rejected system volume change (CoreAudio status \(lastStatus))")
        }
        throw ReceiverError.audio("Default output system volume is not settable")
    }
}

private final class PINAttemptLimiter {
    private let lock = NSLock()
    private var failures = 0
    private let maximumFailures = 5

    func isBlocked() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return failures >= maximumFailures
    }

    func recordFailure() {
        lock.lock()
        failures = min(maximumFailures, failures + 1)
        lock.unlock()
    }
}

private struct AuthorizedDevice: Codable {
    let id: String
    let name: String
    let token: String
}

/// Stores random per-device credentials in a per-user, owner-only file.
/// The LAN protocol is still plaintext; this protects credentials at rest.
private final class PairingStore {
    private let lock = NSLock()
    private let url: URL
    private var devices: [String: AuthorizedDevice]
    private let persistenceAvailable: Bool

    init(url: URL = privateDirectoryURL().appendingPathComponent("paired-devices.json")) {
        self.url = url
        if !FileManager.default.fileExists(atPath: url.path) {
            devices = [:]
            persistenceAvailable = true
        } else if let data = try? Data(contentsOf: url),
                  let values = try? JSONDecoder().decode([AuthorizedDevice].self, from: data) {
            devices = Dictionary(values.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            persistenceAvailable = true
            _ = chmod(url.deletingLastPathComponent().path, mode_t(S_IRWXU))
            _ = chmod(url.path, mode_t(S_IRUSR | S_IWUSR))
        } else {
            devices = [:]
            persistenceAvailable = false
            status("Pairing store is unreadable; new device pairing is disabled")
        }
    }

    func isAuthorized(id: String, token: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let device = devices[id] else { return false }
        return constantTimeEqual(device.token, token)
    }

    func register(id: String, name: String, token: String) -> Bool {
        guard persistenceAvailable, Self.validID(id), Self.validName(name), Self.validToken(token) else { return false }
        lock.lock(); defer { lock.unlock() }
        guard devices[id] == nil, devices.count < 32 else { return false }
        devices[id] = AuthorizedDevice(id: id, name: name, token: token)
        do { try persistLocked(); return true }
        catch { devices.removeValue(forKey: id); return false }
    }

    func revoke(id: String, token: String) -> Bool {
        guard persistenceAvailable else { return false }
        lock.lock(); defer { lock.unlock() }
        guard let device = devices[id], constantTimeEqual(device.token, token) else { return false }
        devices.removeValue(forKey: id)
        do { try persistLocked(); return true }
        catch { devices[id] = device; return false }
    }

    private func persistLocked() throws {
        let directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        _ = chmod(directory.path, mode_t(S_IRWXU))
        let data = try JSONEncoder().encode(devices.values.sorted { $0.id < $1.id })
        let temporary = directory.appendingPathComponent("paired-devices.\(UUID().uuidString).tmp")
        let descriptor = open(temporary.path, O_WRONLY | O_CREAT | O_EXCL, mode_t(S_IRUSR | S_IWUSR))
        guard descriptor >= 0 else { throw ReceiverError.system("create private pairing file", errno) }
        var writeError: Int32 = 0
        data.withUnsafeBytes { rawBuffer in
            guard let base = rawBuffer.baseAddress else { return }
            var offset = 0
            while offset < rawBuffer.count {
                let result = Darwin.write(descriptor, base.advanced(by: offset), rawBuffer.count - offset)
                if result <= 0 { writeError = errno; return }
                offset += result
            }
        }
        if writeError == 0 && fsync(descriptor) != 0 { writeError = errno }
        _ = Darwin.close(descriptor)
        if writeError != 0 {
            try? FileManager.default.removeItem(at: temporary)
            throw ReceiverError.system("write private pairing file", writeError)
        }
        if rename(temporary.path, url.path) != 0 {
            let code = errno
            try? FileManager.default.removeItem(at: temporary)
            throw ReceiverError.system("replace private pairing file", code)
        }
        _ = chmod(url.path, mode_t(S_IRUSR | S_IWUSR))
    }

    private static func validID(_ value: String) -> Bool {
        value.count >= 16 && value.count <= 64 && value.utf8.allSatisfy { byte in
            (byte >= 48 && byte <= 57) || (byte >= 65 && byte <= 90) ||
            (byte >= 97 && byte <= 122) || byte == 45 || byte == 95
        }
    }
    private static func validName(_ value: String) -> Bool {
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            value.utf8.count <= 80 && !value.contains("\n") && !value.contains("\r")
    }
    private static func validToken(_ value: String) -> Bool {
        value.count == 64 && value.utf8.allSatisfy {
            ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102) || ($0 >= 65 && $0 <= 70)
        }
    }
}

private func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
    let a = Array(lhs.utf8), b = Array(rhs.utf8)
    guard a.count == b.count else { return false }
    var difference: UInt8 = 0
    for index in a.indices { difference |= a[index] ^ b[index] }
    return difference == 0
}

private func sendJSONLine(_ object: [String: Any], to descriptor: Int32) {
    guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
          let text = String(data: data, encoding: .utf8) else { return }
    sendLine(text, to: descriptor)
}

private func validInteger(_ value: Any?) -> Int? {
    guard let number = value as? NSNumber,
          CFGetTypeID(number) != CFBooleanGetTypeID(),
          number.doubleValue.isFinite,
          number.doubleValue.rounded(.towardZero) == number.doubleValue,
          number.doubleValue >= Double(Int.min),
          number.doubleValue <= Double(Int.max) else { return nil }
    return number.intValue
}

private func serveControl(from client: Int32, pin: String, limiter: PINAttemptLimiter,
                          pairings: PairingStore) {
    var timeout = timeval(tv_sec: 2, tv_usec: 0)
    _ = setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    _ = setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
    var noSigPipe: Int32 = 1
    _ = setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe,
                   socklen_t(MemoryLayout<Int32>.size))

    guard let protocolName = readLine(from: client, maximumBytes: 16) else {
        sendLine("ERR", to: client)
        status("Rejected control client: invalid protocol")
        return
    }
    var authenticatedDeviceID: String? = nil
    var authenticatedToken: String? = nil
    if protocolName == "LANPAIR1" {
        if limiter.isBlocked() {
            sendLine("ERR", to: client)
            status("Pairing PIN rate limit active")
            return
        }
        guard let suppliedPIN = readLine(from: client, maximumBytes: 6), suppliedPIN == pin,
              let id = readLine(from: client, maximumBytes: 64),
              let name = readLine(from: client, maximumBytes: 80),
              let token = readLine(from: client, maximumBytes: 64),
              pairings.register(id: id, name: name, token: token) else {
            limiter.recordFailure()
            sendLine("ERR", to: client)
            status("Rejected device pairing: invalid PIN or pairing data")
            return
        }
        sendLine("OK", to: client)
        status("Paired Android device: \(name)")
        return
    }
    if protocolName == "LANCTRL1" {
        if limiter.isBlocked() {
            sendLine("ERR", to: client)
            status("Control PIN rate limit active")
            return
        }
        guard let suppliedPIN = readLine(from: client, maximumBytes: 6), suppliedPIN == pin else {
            limiter.recordFailure()
            sendLine("ERR", to: client)
            status("Rejected control client: invalid pairing code")
            return
        }
    } else if protocolName == "LANCTRL2" {
        guard let id = readLine(from: client, maximumBytes: 64),
              let token = readLine(from: client, maximumBytes: 64),
              pairings.isAuthorized(id: id, token: token) else {
            sendLine("ERR", to: client)
            status("Rejected control client: device is not paired")
            return
        }
        authenticatedDeviceID = id
        authenticatedToken = token
    } else {
        limiter.recordFailure()
        sendLine("ERR", to: client)
        status("Rejected control client: unsupported protocol")
        return
    }
    sendLine("OK", to: client)

    guard let line = readLine(from: client, maximumBytes: 4096),
          let data = line.data(using: .utf8),
          let request = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        sendJSONLine(["id": NSNull(), "ok": false, "error": "invalid request"], to: client)
        return
    }
    let requestID = validInteger(request["id"])
    guard let requestID else {
        sendJSONLine(["id": NSNull(), "ok": false, "error": "id must be an integer"], to: client)
        return
    }
    guard let command = request["cmd"] as? String else {
        sendJSONLine(["id": requestID, "ok": false, "error": "cmd must be a string"], to: client)
        return
    }

    do {
        switch command {
        case "unpair":
            guard protocolName == "LANCTRL2", let id = authenticatedDeviceID,
                  pairings.revoke(id: id, token: authenticatedToken ?? "") else {
                sendJSONLine(["id": requestID, "ok": false, "error": "unpair requires paired-device authentication"], to: client)
                return
            }
            sendJSONLine(["id": requestID, "ok": true], to: client)
        case "get_volume":
            let volume = try SystemVolume.getPercent()
            sendJSONLine(["id": requestID, "ok": true, "volume": volume], to: client)
        case "set_volume":
            guard let value = validInteger(request["value"]), (0...100).contains(value) else {
                sendJSONLine(["id": requestID, "ok": false,
                              "error": "value must be an integer from 0 to 100"], to: client)
                return
            }
            let volume = try SystemVolume.setPercent(value)
            sendJSONLine(["id": requestID, "ok": true, "volume": volume], to: client)
        default:
            sendJSONLine(["id": requestID, "ok": false, "error": "unsupported command"], to: client)
        }
    } catch {
        let message = String(describing: error)
        sendJSONLine(["id": requestID, "ok": false, "error": message], to: client)
    }
}

private struct InterfaceAddress {
    let name: String
    let address: in_addr
}

private func usableLANAddresses() -> [InterfaceAddress] {
    var head: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&head) == 0, let first = head else { return [] }
    defer { freeifaddrs(head) }

    var found: [InterfaceAddress] = []
    var current: UnsafeMutablePointer<ifaddrs>? = first
    while let item = current {
        let info = item.pointee
        current = info.ifa_next
        guard let address = info.ifa_addr,
              address.pointee.sa_family == UInt8(AF_INET),
              (info.ifa_flags & UInt32(IFF_UP)) != 0,
              (info.ifa_flags & UInt32(IFF_LOOPBACK)) == 0 else { continue }

        let name = String(cString: info.ifa_name)
        guard name.hasPrefix("en") else { continue }
        let socketAddress = UnsafeRawPointer(address).assumingMemoryBound(to: sockaddr_in.self).pointee
        let octets = withUnsafeBytes(of: socketAddress.sin_addr) { Array($0) }
        guard octets.count == 4,
              octets[0] != 169, // skip self-assigned link-local addresses
              octets[0] != 0 else { continue }
        found.append(InterfaceAddress(name: name, address: socketAddress.sin_addr))
    }

    return found.sorted { lhs, rhs in
        if lhs.name == "en0" { return true }
        if rhs.name == "en0" { return false }
        return lhs.name < rhs.name
    }
}

private func addressString(_ address: in_addr) -> String {
    var value = address
    var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
    guard inet_ntop(AF_INET, &value, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
        return "unknown"
    }
    return String(cString: buffer)
}

private func freshPIN() throws -> String {
    var bytes = [UInt8](repeating: 0, count: 4)
    let status = bytes.withUnsafeMutableBytes { rawBuffer in
        SecRandomCopyBytes(kSecRandomDefault, rawBuffer.count, rawBuffer.baseAddress!)
    }
    guard status == errSecSuccess else {
        throw ReceiverError.random("Secure random PIN generation failed (OSStatus \(status))")
    }
    let value = (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16) |
                (UInt32(bytes[2]) << 8) | UInt32(bytes[3])
    return String(format: "%06u", value % 1_000_000)
}

private func makeListener(boundTo address: in_addr, port: UInt16) throws -> Int32 {
    let descriptor = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    guard descriptor >= 0 else { throw ReceiverError.system("socket", errno) }

    var noSigPipe: Int32 = 1
    _ = setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe,
                   socklen_t(MemoryLayout<Int32>.size))
    var reuseAddress: Int32 = 1
    _ = setsockopt(descriptor, SOL_SOCKET, SO_REUSEADDR, &reuseAddress,
                   socklen_t(MemoryLayout<Int32>.size))

    var socketAddress = sockaddr_in()
    socketAddress.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    socketAddress.sin_family = sa_family_t(AF_INET)
    socketAddress.sin_port = port.bigEndian
    socketAddress.sin_addr = address

    let bindResult = withUnsafePointer(to: &socketAddress) { pointer in
        pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    guard bindResult == 0 else {
        let code = errno
        close(descriptor)
        throw ReceiverError.system("bind TCP port \(port)", code)
    }
    guard listen(descriptor, 1) == 0 else {
        let code = errno
        close(descriptor)
        throw ReceiverError.system("listen", code)
    }
    return descriptor
}

private func sendLine(_ text: String, to descriptor: Int32) {
    let bytes = Array((text + "\n").utf8)
    bytes.withUnsafeBytes { rawBuffer in
        guard let base = rawBuffer.baseAddress else { return }
        var sent = 0
        while sent < rawBuffer.count {
            let result = send(descriptor, base.advanced(by: sent), rawBuffer.count - sent, 0)
            if result <= 0 { return }
            sent += result
        }
    }
}

private func readLine(from descriptor: Int32, maximumBytes: Int) -> String? {
    var bytes: [UInt8] = []
    while bytes.count <= maximumBytes {
        var byte: UInt8 = 0
        let received = recv(descriptor, &byte, 1, 0)
        guard received == 1 else { return nil }
        if byte == 10 { return String(bytes: bytes, encoding: .utf8) }
        bytes.append(byte)
    }
    return nil
}

private func serveAudio(from client: Int32, pin: String, output: AudioOutput,
                        stopState: StopState, pairings: PairingStore,
                        limiter: PINAttemptLimiter) {
    var receiveTimeout = timeval(tv_sec: 1, tv_usec: 0)
    _ = setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &receiveTimeout,
                   socklen_t(MemoryLayout<timeval>.size))
    var noSigPipe: Int32 = 1
    _ = setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe,
                   socklen_t(MemoryLayout<Int32>.size))

    let protocolName = readLine(from: client, maximumBytes: 16)
    let authorized: Bool
    if protocolName == "LANPCM1" {
        if limiter.isBlocked() {
            sendLine("ERR", to: client)
            status("Audio PIN rate limit active")
            return
        }
        authorized = readLine(from: client, maximumBytes: 6) == pin
        if !authorized { limiter.recordFailure() }
    } else if protocolName == "LANPCM2",
              let id = readLine(from: client, maximumBytes: 64),
              let token = readLine(from: client, maximumBytes: 64) {
        authorized = pairings.isAuthorized(id: id, token: token)
    } else {
        authorized = false
    }
    guard authorized else {
        sendLine("ERR", to: client)
        status("Rejected sender: invalid protocol, pairing code or paired device")
        return
    }

    sendLine("OK", to: client)
    setDesktopConnected(true)
    status("Sender paired; playing 48 kHz stereo PCM (Ctrl+C stops receiver)")

    var readBuffer = [UInt8](repeating: 0, count: 4_096)
    var pendingBytes: [UInt8] = []
    var receivedFrames: UInt64 = 0

    while !stopState.isStopped {
        let count = recv(client, &readBuffer, readBuffer.count, 0)
        if count == 0 { break }
        if count < 0 {
            if errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK { continue }
            break
        }

        pendingBytes.append(contentsOf: readBuffer[0..<count])
        let completeByteCount = (pendingBytes.count / 4) * 4
        if completeByteCount > 0 {
            let complete = Array(pendingBytes[0..<completeByteCount])
            let frames = completeByteCount / 4
            output.enqueue(complete, frameCount: frames)
            receivedFrames &+= UInt64(frames)
            pendingBytes.removeFirst(completeByteCount)
        }
    }

    output.drainCurrentStream(timeout: 0.30)
    setDesktopConnected(false)
    status("Sender disconnected; received \(receivedFrames) frames, rendered from queue: \(output.renderedFrames), dropped late frames: \(output.droppedFrames); \(output.callbackDiagnostics)")
}

/// Protocol-test harness: runs only the control listener on loopback and
/// stores its one-use test PIN and pairing store under private app support.
private func runControlTestServer() throws {
    var loopback = in_addr()
    guard inet_pton(AF_INET, "127.0.0.1", &loopback) == 1 else {
        throw ReceiverError.audio("Could not create loopback address")
    }
    let listener = try makeListener(boundTo: loopback, port: controlPort)
    defer { close(listener) }
    let pin = try freshPIN()
    let directory = privateDirectoryURL()
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    _ = chmod(directory.path, mode_t(S_IRWXU))
    let statusURL = directory.appendingPathComponent("control-test-\(getpid()).json")
    let value = ReceiverStatus(pid: getpid(), state: "ready", ip: "127.0.0.1", port: controlPort,
                               pin: pin, service: "loopback-test-only", updatedAt: Date())
    try JSONEncoder().encode(value).write(to: statusURL, options: .atomic)
    _ = chmod(statusURL.path, mode_t(S_IRUSR | S_IWUSR))
    defer { try? FileManager.default.removeItem(at: statusURL) }

    let stopState = StopState()
    signal(SIGINT, SIG_IGN)
    signal(SIGTERM, SIG_IGN)
    let interrupt = DispatchSource.makeSignalSource(signal: SIGINT, queue: .global())
    let terminate = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .global())
    interrupt.setEventHandler { stopState.requestStop() }
    terminate.setEventHandler { stopState.requestStop() }
    interrupt.resume()
    terminate.resume()
    defer {
        interrupt.cancel()
        terminate.cancel()
    }

    status("Loopback control test server ready on 127.0.0.1:\(controlPort)")
    let limiter = PINAttemptLimiter()
    let pairingsURL = directory.appendingPathComponent("test-paired-devices.json")
    let pairings = PairingStore(url: pairingsURL)
    defer { try? FileManager.default.removeItem(at: pairingsURL) }
    var pollDescriptor = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
    while !stopState.isStopped {
        pollDescriptor.revents = 0
        let ready = poll(&pollDescriptor, 1, 500)
        if ready < 0 {
            if errno == EINTR { continue }
            throw ReceiverError.system("poll control test listener", errno)
        }
        guard ready > 0, (pollDescriptor.revents & Int16(POLLIN)) != 0 else { continue }
        let client = accept(listener, nil, nil)
        guard client >= 0 else {
            if errno == EINTR { continue }
            if stopState.isStopped { break }
            throw ReceiverError.system("accept control test client", errno)
        }
        serveControl(from: client, pin: pin, limiter: limiter, pairings: pairings)
        close(client)
    }
}

private func runReceiver(stopState: StopState) throws {
    let interfaces = usableLANAddresses()
    guard let interface = interfaces.first else {
        throw ReceiverError.audio("No active non-loopback Ethernet/Wi-Fi IPv4 interface found")
    }
    let ip = addressString(interface.address)
    let pin = try freshPIN()
    let listener = try makeListener(boundTo: interface.address, port: listenPort)
    defer { close(listener) }
    let controlListener = try makeListener(boundTo: interface.address, port: controlPort)
    defer { close(controlListener) }
    removePrivateQR()
    defer { removePrivateQR() }

    let output = try AudioOutput()
    defer { output.stopEngine() }

    try writePrivateStatus(ip: ip, pin: pin, state: "starting")
    defer {
        let url = statusFileURL()
        if let data = try? Data(contentsOf: url),
           let value = try? JSONDecoder().decode(ReceiverStatus.self, from: data),
           value.pid == getpid() {
            try? FileManager.default.removeItem(at: url)
        }
    }

    let publisher = try BonjourPublisher(name: localReceiverName(), onReady: {
        do {
            try writePrivateStatus(ip: ip, pin: pin, state: "ready")
            status("Bonjour ready: _openbot-lanaudio._tcp. on port \(listenPort)")
        } catch {
            status("Could not update private receiver status: \(error)")
        }
    }, onFailure: { message in
        status(message)
    })
    defer { publisher.stop() }

    setDesktopConnected(false)
    let pairings = PairingStore()
    let authenticationLimiter = PINAttemptLimiter()
    let controlGroup = DispatchGroup()
    controlGroup.enter()
    defer { controlGroup.wait() }
    DispatchQueue.global().async {
        defer { controlGroup.leave() }
        var pollDescriptor = pollfd(fd: controlListener, events: Int16(POLLIN), revents: 0)
        while !stopState.isStopped {
            pollDescriptor.revents = 0
            let ready = poll(&pollDescriptor, 1, 500)
            if ready < 0 {
                if errno == EINTR { continue }
                status("Control listener poll failed (errno \(errno))")
                break
            }
            guard ready > 0, (pollDescriptor.revents & Int16(POLLIN)) != 0 else { continue }
            let client = accept(controlListener, nil, nil)
            guard client >= 0 else {
                if errno == EINTR { continue }
                if stopState.isStopped { break }
                status("Could not accept control client (errno \(errno))")
                continue
            }
            serveControl(from: client, pin: pin, limiter: authenticationLimiter, pairings: pairings)
            close(client)
        }
    }
    signal(SIGINT, SIG_IGN)
    signal(SIGTERM, SIG_IGN)
    let interrupt = DispatchSource.makeSignalSource(signal: SIGINT, queue: .global())
    let terminate = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .global())
    interrupt.setEventHandler { stopState.requestStop() }
    terminate.setEventHandler { stopState.requestStop() }
    interrupt.resume()
    terminate.resume()
    defer {
        interrupt.cancel()
        terminate.cancel()
    }

    status("Android → iMac LAN audio receiver")
    status("Status: listening on \(ip):\(listenPort) via \(interface.name)")
    status("Control listener: \(ip):\(controlPort)")
    if isatty(STDOUT_FILENO) == 1 {
        status("Pairing PIN (new for this launch): \(pin)")
    } else {
        status("Pairing PIN is private; run --status in Terminal to view it")
    }
    status("Audio: signed 16-bit little-endian, stereo, 48,000 Hz")
    status("Security: unencrypted LAN prototype; use only on a trusted network")
    status(isatty(STDOUT_FILENO) == 1 ? "Stop: press Ctrl+C" : "Stop: launchctl bootout the user LaunchAgent")

    var pollDescriptor = pollfd(fd: listener, events: Int16(POLLIN), revents: 0)
    while !stopState.isStopped {
        pollDescriptor.revents = 0
        let ready = poll(&pollDescriptor, 1, 500)
        if ready < 0 {
            if errno == EINTR { continue }
            throw ReceiverError.system("poll", errno)
        }
        guard ready > 0, (pollDescriptor.revents & Int16(POLLIN)) != 0 else { continue }

        let client = accept(listener, nil, nil)
        guard client >= 0 else {
            if errno == EINTR { continue }
            if stopState.isStopped { break }
            status("Could not accept sender (errno \(errno))")
            continue
        }
        serveAudio(from: client, pin: pin, output: output, stopState: stopState,
                   pairings: pairings, limiter: authenticationLimiter)
        close(client)
    }
    controlGroup.wait()
    status("Receiver stopped")
}

func runReceiverCLI(arguments: [String]) -> Int32 {
    if arguments.count == 2 {
        switch arguments[1] {
        case "--status": return readStatusCommand()
        case "--show-qr": return showQRCommand()
        case "--remove-qr": return removeQRCommand()
        case "--test-qr": return showQRCommand(testOnly: true)
        case "--test-volume": return testSystemVolumeCommand()
        case "--test-pairing-store": return testPairingStoreCommand()
        case "--test-control-server":
            do { try runControlTestServer(); return EXIT_SUCCESS }
            catch { fputs("Control protocol test server error: \(error)\n", stderr); return EXIT_FAILURE }
        default: break
        }
    } else if arguments.count == 1 {
        do {
            try runReceiver(stopState: StopState())
            return EXIT_SUCCESS
        } catch {
            fputs("Receiver error: \(error)\n", stderr)
            return EXIT_FAILURE
        }
    }
    fputs("Usage: android-imac-audio-receiver [--status|--show-qr|--remove-qr|--test-qr|--test-volume|--test-pairing-store|--test-control-server]\n", stderr)
    return EX_USAGE
}
