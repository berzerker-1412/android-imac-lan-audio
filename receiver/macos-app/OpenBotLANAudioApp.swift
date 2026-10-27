import AppKit
import Darwin
import Foundation

@main
struct OpenBotLANAudioApp {
    static func main() {
        let application = NSApplication.shared
        let delegate = AppDelegate()
        application.delegate = delegate
        application.setActivationPolicy(.regular)
        application.run()
    }
}

private final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow!
    private var statusLabel = NSTextField(labelWithString: "Stopped")
    private var hostLabel = NSTextField(labelWithString: "")
    private var outputLabel = NSTextField(labelWithString: "Checking audio output…")
    private var detailLabel = NSTextField(wrappingLabelWithString: "")
    private var errorLabel = NSTextField(wrappingLabelWithString: "")
    private var startButton = NSButton(title: "Start receiver", target: nil, action: nil)
    private var stopButton = NSButton(title: "Stop receiver", target: nil, action: nil)
    private var pairingButton = NSButton(title: "Show pairing QR", target: nil, action: nil)
    private var pairingBox = NSBox()
    private var qrImage = NSImageView()
    private var pinLabel = NSTextField(labelWithString: "")
    private var hidePairingButton = NSButton(title: "Hide pairing", target: nil, action: nil)
    private var timer: Timer?
    private var lastEngineMessage = ""
    private var appStartedReceiver = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        buildWindow()
        setDesktopStatusHandler { [weak self] message in
            DispatchQueue.main.async {
                guard let self else { return }
                if message.contains("Sender paired; playing") { self.lastEngineMessage = "Connected · receiving audio" }
                else if message.contains("Sender disconnected") { self.lastEngineMessage = "Ready · waiting for phone" }
                else if message.contains("Bonjour ready") { self.lastEngineMessage = "Ready · visible on this network" }
                self.refresh()
            }
        }
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in self?.refresh() }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        setDesktopStatusHandler(nil)
        if appStartedReceiver { stopDesktopReceiverAndWait() }
        return .terminateNow
    }

    private func buildWindow() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 540, height: 590),
                          styleMask: [.titled, .closable, .miniaturizable],
                          backing: .buffered, defer: false)
        window.title = "OpenBot LAN Audio"
        window.isReleasedWhenClosed = false

        let root = NSStackView()
        root.orientation = .vertical
        root.alignment = .leading
        root.spacing = 13
        root.edgeInsets = NSEdgeInsets(top: 24, left: 26, bottom: 24, right: 26)
        root.translatesAutoresizingMaskIntoConstraints = false

        let title = NSTextField(labelWithString: "Send audio to this Mac")
        title.font = .systemFont(ofSize: 23, weight: .semibold)
        root.addArrangedSubview(title)

        statusLabel.font = .systemFont(ofSize: 18, weight: .medium)
        hostLabel.font = .systemFont(ofSize: 13)
        hostLabel.textColor = .secondaryLabelColor
        outputLabel.font = .systemFont(ofSize: 13)
        outputLabel.textColor = .secondaryLabelColor
        detailLabel.font = .systemFont(ofSize: 12)
        detailLabel.textColor = .secondaryLabelColor
        errorLabel.font = .systemFont(ofSize: 12)
        errorLabel.textColor = .systemRed
        for label in [statusLabel, hostLabel, outputLabel, detailLabel, errorLabel] {
            label.lineBreakMode = .byWordWrapping
            label.maximumNumberOfLines = 3
            label.setContentHuggingPriority(.defaultLow, for: .horizontal)
            root.addArrangedSubview(label)
        }

        let buttons = NSStackView(views: [startButton, stopButton])
        buttons.orientation = .horizontal
        buttons.distribution = .fillEqually
        buttons.spacing = 10
        startButton.bezelStyle = .rounded
        stopButton.bezelStyle = .rounded
        startButton.target = self
        startButton.action = #selector(startReceiver)
        stopButton.target = self
        stopButton.action = #selector(stopReceiver)
        root.addArrangedSubview(buttons)

        pairingButton.bezelStyle = .rounded
        pairingButton.target = self
        pairingButton.action = #selector(showPairing)
        root.addArrangedSubview(pairingButton)

        pairingBox.title = "Pair Android phone"
        pairingBox.boxType = .primary
        pairingBox.contentViewMargins = NSSize(width: 14, height: 12)
        let pairingContent = NSStackView()
        pairingContent.orientation = .vertical
        pairingContent.alignment = .centerX
        pairingContent.spacing = 8
        qrImage.imageScaling = .scaleProportionallyUpOrDown
        qrImage.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        qrImage.widthAnchor.constraint(equalToConstant: 230).isActive = true
        qrImage.heightAnchor.constraint(equalToConstant: 230).isActive = true
        pinLabel.font = .monospacedSystemFont(ofSize: 19, weight: .semibold)
        pinLabel.alignment = .center
        let explanation = NSTextField(wrappingLabelWithString: "Scan this code in the Android app. The pairing code appears only while this panel is open.")
        explanation.font = .systemFont(ofSize: 12)
        explanation.textColor = .secondaryLabelColor
        explanation.alignment = .center
        hidePairingButton.bezelStyle = .rounded
        hidePairingButton.target = self
        hidePairingButton.action = #selector(hidePairing)
        pairingContent.addArrangedSubview(qrImage)
        pairingContent.addArrangedSubview(pinLabel)
        pairingContent.addArrangedSubview(explanation)
        pairingContent.addArrangedSubview(hidePairingButton)
        pairingBox.contentView = pairingContent
        pairingBox.isHidden = true
        root.addArrangedSubview(pairingBox)

        let caution = NSTextField(wrappingLabelWithString: "LAN prototype: audio and pairing traffic are unencrypted. Use only on a trusted network.")
        caution.font = .systemFont(ofSize: 11)
        caution.textColor = .secondaryLabelColor
        root.addArrangedSubview(caution)

        let content = NSView()
        content.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            root.topAnchor.constraint(equalTo: content.topAnchor),
            root.bottomAnchor.constraint(lessThanOrEqualTo: content.bottomAnchor)
        ])
        window.contentView = content
    }

    @objc private func startReceiver() {
        errorLabel.stringValue = ""
        lastEngineMessage = "Starting receiver…"
        let started = startDesktopReceiver { [weak self] message in
            DispatchQueue.main.async {
                guard let self else { return }
                self.errorLabel.stringValue = message.contains("bind TCP port")
                    ? "Another receiver is using this Mac's audio port. Stop it from the app or session that started it."
                    : "Receiver could not start: \(message)"
                self.lastEngineMessage = "Stopped"
                self.appStartedReceiver = false
                self.refresh()
            }
        }
        appStartedReceiver = started
        lastEngineMessage = started ? "Starting receiver…" : "Receiver is already running in this app."
        refresh()
    }

    @objc private func stopReceiver() {
        guard appStartedReceiver else { return }
        lastEngineMessage = "Stopping receiver…"
        refresh()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            stopDesktopReceiverAndWait()
            DispatchQueue.main.async {
                guard let self else { return }
                self.appStartedReceiver = false
                self.lastEngineMessage = "Stopped"
                self.hidePairing()
                self.refresh()
            }
        }
    }

    @objc private func showPairing() {
        do {
            guard let status = currentDesktopReceiverStatus() else {
                throw NSError(domain: "OpenBotLANAudio", code: 3,
                              userInfo: [NSLocalizedDescriptionKey: "Receiver is not ready yet."])
            }
            qrImage.image = try pairingQRImageForCurrentReceiver()
            pinLabel.stringValue = "Pairing code: \(status.pin)"
            pairingBox.isHidden = false
            errorLabel.stringValue = ""
            refresh()
        } catch {
            errorLabel.stringValue = error.localizedDescription
        }
    }

    @objc private func hidePairing() {
        pairingBox.isHidden = true
        qrImage.image = nil
        pinLabel.stringValue = ""
    }

    private func refresh() {
        let running = desktopReceiverIsRunning()
        let status = currentDesktopReceiverStatus()
        let externalAlive = status.map { $0.pid != getpid() && kill($0.pid, 0) == 0 } ?? false
        let portBusy = (!running && !externalAlive) && (isPortListening(50555) || isPortListening(50556))

        if desktopSenderIsConnected() {
            statusLabel.stringValue = "Connected · receiving audio"
        } else if running {
            statusLabel.stringValue = status?.state == "ready" ? "Ready · visible on this network" : "Starting receiver…"
        } else if externalAlive || portBusy {
            statusLabel.stringValue = "Receiver running outside this app"
        } else {
            statusLabel.stringValue = "Stopped"
        }
        let address = status?.ip ?? currentIPv4Address() ?? "IP unavailable"
        hostLabel.stringValue = "\(Host.current().localizedName ?? "This Mac") · \(address) · TCP 50555"
        if let volume = currentSystemVolumePercent() {
            outputLabel.stringValue = "Output: \(currentSystemOutputName()) · Volume \(volume)%"
        } else {
            outputLabel.stringValue = "Output: \(currentSystemOutputName()) · volume unavailable"
        }
        if externalAlive || portBusy {
            detailLabel.stringValue = "The receiver is owned by another app or login session. This window will not stop it."
        } else {
            detailLabel.stringValue = "Bonjour: _openbot-lanaudio._tcp. · PCM 48 kHz stereo"
        }
        startButton.isEnabled = !running && !externalAlive && !portBusy
        stopButton.isEnabled = running && appStartedReceiver
        pairingButton.isEnabled = status?.state == "ready" && (running || externalAlive)
    }

    private func isPortListening(_ port: UInt16) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/lsof")
        process.arguments = ["-nP", "-iTCP:\(port)", "-sTCP:LISTEN"]
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
            let data = output.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            return !data.isEmpty
        } catch { return false }
    }

    private func currentIPv4Address() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let item = cursor {
            let interface = item.pointee
            if let address = interface.ifa_addr, address.pointee.sa_family == UInt8(AF_INET),
               let name = String(validatingUTF8: interface.ifa_name), name.hasPrefix("en") {
                var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0,
                   let value = String(validatingUTF8: host), !value.hasPrefix("127."), value != "0.0.0.0" {
                    return value
                }
            }
            cursor = interface.ifa_next
        }
        return nil
    }
}
