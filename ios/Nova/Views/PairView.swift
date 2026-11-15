import AVFoundation
import SwiftUI
import UIKit

/// The first screen: pair this iPhone with a Nova server — scan its code, type its address, or paste a setup code.
struct PairView: View {
    @EnvironmentObject var app: AppState
    @State private var name = UIDevice.current.name
    @State private var scanning = false
    @State private var busy = false
    @State private var error: String?
    @State private var address = ""
    @State private var code = ""
    @State private var setup = ""
    @State private var check: (info: PairInfo, short: String)?         // typed pairing: the certificate to compare by eye

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 10) {
                        Image(systemName: "server.rack").font(.system(size: 44)).foregroundStyle(.tint)
                        Text("Pair with your server").font(.title2.weight(.bold))
                        Text("On the server, open Nova Server and press Pair a phone — or run  sudo nova add  in a terminal. Then scan the code it shows.")
                            .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
                    .listRowBackground(Color.clear)
                }
                Section {
                    Button { scanning = true } label: { Label("Scan the code", systemImage: "qrcode.viewfinder").font(.headline) }
                        .accessibilityIdentifier("scan")
                }
                Section("Or type it") {
                    TextField("Server address (e.g. 192.168.1.20)", text: $address)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .accessibilityIdentifier("address")
                    TextField("Pairing code", text: $code)
                        .textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                        .accessibilityIdentifier("code")
                    Button("Pair") { typed() }
                        .disabled(busy || address.trimmingCharacters(in: .whitespaces).isEmpty || code.trimmingCharacters(in: .whitespaces).count < 6)
                        .accessibilityIdentifier("pairTyped")
                }
                Section {
                    TextField("nova1:…", text: $setup, axis: .vertical)
                        .lineLimit(1...4).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .font(.system(.footnote, design: .monospaced))
                    Button("Pair with this setup code") { useSetup(setup) }.disabled(busy || setup.count < 20)
                } header: { Text("Away from home") } footer: {
                    Text("A setup code from  sudo nova add --remote  pairs over Tailscale or your Cloudflare address.")
                }
                Section("This iPhone's name on the server") {
                    TextField("Name", text: $name).accessibilityIdentifier("deviceName")
                }
                if busy { Section { HStack { ProgressView(); Text("Pairing…").foregroundStyle(.secondary) } } }
                if let e = error { Section { Label(e, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).accessibilityIdentifier("pairError") } }
            }
            .navigationTitle("Nova")
            .navigationBarTitleDisplayMode(.inline)
        }
        .sheet(isPresented: $scanning) {
            ScannerSheet { text in
                scanning = false
                guard let info = PairInfo.parse(text) else { error = "That isn't a Nova pairing code."; return }
                pair(info)
            }
        }
        .alert("Does the server show this?", isPresented: Binding(get: { check != nil }, set: { if !$0 { check = nil } })) {
            Button("It matches") { if let c = check { pair(c.info) } }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("\(check?.short ?? "")\n\nIt's the start of the server's certificate. The server shows the same letters next to the pairing code — if they differ, something else answered at that address.")
        }
        .task {
            #if DEBUG
            // UI tests hand the pairing code over at launch (the Simulator has no camera to scan it with)
            if let raw = ProcessInfo.processInfo.environment["NOVA_PAIR"], let info = PairInfo.parse(raw) { pair(info) }
            #endif
        }
    }

    private func pair(_ info: PairInfo) {
        busy = true; error = nil
        let n = name.trimmingCharacters(in: .whitespaces).isEmpty ? "iPhone" : String(name.prefix(40))
        Task {
            do { app.adopt(try await NovaAPI.pair(info, name: n)) }
            catch { self.error = (error as? ApiError)?.message ?? error.localizedDescription; Keys.remove(.device) }
            busy = false
        }
    }

    private func useSetup(_ text: String) {
        guard let info = PairInfo.parse(text) else { error = "That isn't a Nova setup code."; return }
        pair(info)
    }

    /// Typed pairing: nothing vouches for the certificate yet, so show its first letters to compare with the server's.
    private func typed() {
        var host = address.trimmingCharacters(in: .whitespaces)
        if host.hasPrefix("http://") { host = String(host.dropFirst(7)) }
        var url = normURL(host)
        if URL(string: url)?.port == nil { url += ":8495" }
        let c = code.trimmingCharacters(in: .whitespaces).uppercased()
        busy = true; error = nil
        Task {
            if let cert = await NovaAPI.certificate(at: url) {
                check = (PairInfo(lan: url, pin: cert.full, remote: "", tailnet: isTailscale(url) ? url : "", cfId: "", cfSecret: "", code: c), cert.short)
            } else {
                error = "Nothing answered at \(url). Check the address, and that this iPhone is on the server's network (or Tailscale)."
            }
            busy = false
        }
    }
}

/// The camera, looking for a QR code.
struct ScannerSheet: View {
    let found: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var denied = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if denied {
                    VStack(spacing: 10) {
                        Image(systemName: "camera.fill").font(.largeTitle)
                        Text("Nova can't use the camera").font(.headline)
                        Text("Allow it in Settings → Nova → Camera, or type the address and code instead.").font(.subheadline).multilineTextAlignment(.center)
                    }.foregroundStyle(.white).padding(30)
                } else {
                    QRCamera(found: found, denied: $denied).ignoresSafeArea()
                    RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(.white.opacity(0.9), lineWidth: 3).frame(width: 240, height: 240)
                }
            }
            .navigationTitle("Scan the pairing code").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

struct QRCamera: UIViewControllerRepresentable {
    let found: (String) -> Void
    @Binding var denied: Bool

    func makeUIViewController(context: Context) -> QRCameraController {
        let c = QRCameraController()
        c.found = found
        c.noCamera = { denied = true }
        return c
    }
    func updateUIViewController(_ c: QRCameraController, context: Context) { }
}

final class QRCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var found: ((String) -> Void)?
    var noCamera: (() -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var done = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for: .video) { ok in
            DispatchQueue.main.async { if ok { self.begin() } else { self.noCamera?() } }
        }
    }

    private func begin() {
        guard let cam = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: cam), session.canAddInput(input) else { noCamera?(); return }
        session.addInput(input)
        let out = AVCaptureMetadataOutput()
        guard session.canAddOutput(out) else { noCamera?(); return }
        session.addOutput(out)
        out.setMetadataObjectsDelegate(self, queue: .main)
        out.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { DispatchQueue.global(qos: .userInitiated).async { self.session.stopRunning() } }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !done, let o = objects.first as? AVMetadataMachineReadableCodeObject, let text = o.stringValue else { return }
        done = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        found?(text)
    }
}
