import UIKit
import WebKit
import CoreLocation
import CoreMotion
import UserNotifications

class VenjsXEngine: NSObject, WKScriptMessageHandler, WKUIDelegate, CLLocationManagerDelegate {
  weak var rootViewController: UIViewController?
  weak var containerView: UIView?
  weak var bridgeWebView: WKWebView?

  private var eventMap: [ObjectIdentifier: [String: Int]] = [:]

  private let fileManager = FileManager.default
  private var locationManager: CLLocationManager?
  private var pendingLocationEventId: Int?
  private var pendingLocationTimeout: DispatchWorkItem?
  private var pendingLocationParams: [String: Any] = [:]

  private var shakeMotionManager: CMMotionManager?
  private var shakeListenerEventIds: Set<Int> = []
  private var lastShakeTimestampMs: Int64 = 0

  private var notificationReceiveListenerEventIds: Set<Int> = []
  private var notificationTapListenerEventIds: Set<Int> = []
  private var pendingNotificationReceivePayloads: [[String: Any]] = []
  private var pendingNotificationTapPayload: [String: Any]?
  private var apnsToken: String?
  private var pendingPushTokenEventIds: [Int] = []

  init(controller: UIViewController, container: UIView, bridgeWebView: WKWebView) {
    self.rootViewController = controller
    self.containerView = container
    self.bridgeWebView = bridgeWebView
  }

  func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
    if message.name == "processUINode", let jsonString = message.body as? String {
      if let data = jsonString.data(using: .utf8) {
        do {
          if let json = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any] {
            DispatchQueue.main.async {
              self.updateUI(with: json)
            }
          }
        } catch {
          print("venjsX Error: \(error)")
        }
      }
    } else if message.name == "openExternalURL", let urlString = message.body as? String, let url = URL(string: urlString) {
      DispatchQueue.main.async {
        if UIApplication.shared.canOpenURL(url) {
          UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
      }
    } else if message.name == "deviceRequest", let jsonString = message.body as? String {
      DispatchQueue.main.async {
        self.handleDeviceRequest(jsonString)
      }
    }
  }

  private func handleDeviceRequest(_ jsonString: String) {
    guard
      let data = jsonString.data(using: .utf8),
      let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
      let action = json["action"] as? String
    else { return }

    let rawEventId = json["eventId"]
    let eventId = (rawEventId as? NSNumber)?.intValue ?? (rawEventId as? Int) ?? 0
    let params = json["params"] as? [String: Any] ?? [:]

    if action != "log", eventId <= 0 { return }

    switch action {
    case "createFile":
      handleCreateFile(eventId: eventId, params: params)
    case "listFiles":
      handleListFiles(eventId: eventId)
    case "readFile":
      handleReadFile(eventId: eventId, params: params)
    case "writeFile":
      handleWriteFile(eventId: eventId, params: params)
    case "getLocation":
      handleGetLocation(eventId: eventId, params: params)
    case "startShake":
      handleStartShake(eventId: eventId)
    case "stopShake":
      handleStopShake(eventId: eventId)
    case "requestNotificationPermission":
      handleRequestNotificationPermission(eventId: eventId)
    case "scheduleLocalNotification":
      handleScheduleLocalNotification(eventId: eventId, params: params)
    case "cancelLocalNotification":
      handleCancelLocalNotification(eventId: eventId, params: params)
    case "startNotificationListener":
      handleStartNotificationListener(eventId: eventId, params: params)
    case "stopNotificationListener":
      handleStopNotificationListener(eventId: eventId, params: params)
    case "getPushToken":
      handleGetPushToken(eventId: eventId)
    case "log":
      handleNativeLog(params: params)
    default:
      if eventId > 0 {
        emitDeviceError(eventId: eventId, code: "E_ACTION", message: "Unknown action: \(action)")
      }
    }
  }

  private func handleNativeLog(params: [String: Any]) {
    let level = (params["level"] as? String) ?? "log"
    let message = (params["message"] as? String) ?? ""
    print("venjsX[\(level)]: \(message)")
  }

  private func emitDeviceEvent(eventId: Int, payload: [String: Any]) {
    guard
      let data = try? JSONSerialization.data(withJSONObject: payload, options: []),
      let json = String(data: data, encoding: .utf8)
    else { return }

    let js = "window.__venjsDispatchNativeEvent && window.__venjsDispatchNativeEvent(\(eventId), \(json));"
    bridgeWebView?.evaluateJavaScript(js, completionHandler: nil)
  }

  private func emitDeviceOk(eventId: Int, extra: [String: Any]) {
    var payload = extra
    payload["ok"] = true
    payload["platform"] = "ios"
    payload["timestamp"] = Int(Date().timeIntervalSince1970 * 1000)
    emitDeviceEvent(eventId: eventId, payload: payload)
  }

  private func emitDeviceError(eventId: Int, code: String, message: String) {
    let payload: [String: Any] = [
      "ok": false,
      "code": code,
      "error": message,
      "platform": "ios",
      "timestamp": Int(Date().timeIntervalSince1970 * 1000)
    ]
    emitDeviceEvent(eventId: eventId, payload: payload)
  }

  private func deviceRootDir() -> URL? {
    guard let base = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else { return nil }
    let dir = base.appendingPathComponent("venjsX", isDirectory: true)
    if !fileManager.fileExists(atPath: dir.path) {
      try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    return dir
  }

  private func sanitizeFileName(_ raw: String) -> String? {
    let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if name.isEmpty { return nil }
    if name.contains("..") { return nil }
    if name.contains("/") || name.contains("\\") { return nil }
    if name.contains("\0") { return nil }
    return name
  }

  private func resolveTargetFile(params: [String: Any]) -> URL? {
    guard let dir = deviceRootDir() else { return nil }

    if let nameRaw = params["name"] as? String, let name = sanitizeFileName(nameRaw) {
      return dir.appendingPathComponent(name, isDirectory: false)
    }

    if let pathRaw = params["path"] as? String {
      let fileUrl = URL(fileURLWithPath: pathRaw).standardizedFileURL
      let dirUrl = dir.standardizedFileURL
      if fileUrl.path.hasPrefix(dirUrl.path) { return fileUrl }
    }

    return nil
  }

  private func handleCreateFile(eventId: Int, params: [String: Any]) {
    guard let dir = deviceRootDir() else {
      emitDeviceError(eventId: eventId, code: "E_IO", message: "Documents directory unavailable.")
      return
    }
    guard let nameRaw = params["name"] as? String, let name = sanitizeFileName(nameRaw) else {
      emitDeviceError(eventId: eventId, code: "E_NAME", message: "Invalid file name.")
      return
    }

    let content = (params["write"] as? String) ?? ""
    let overwrite = (params["overwrite"] as? Bool) ?? true
    let fileUrl = dir.appendingPathComponent(name, isDirectory: false)

    if fileManager.fileExists(atPath: fileUrl.path), !overwrite {
      emitDeviceError(eventId: eventId, code: "E_EXISTS", message: "File already exists.")
      return
    }

    do {
      try content.write(to: fileUrl, atomically: true, encoding: .utf8)
      let attrs = try? fileManager.attributesOfItem(atPath: fileUrl.path)
      emitDeviceOk(eventId: eventId, extra: [
        "name": name,
        "path": fileUrl.path,
        "size": (attrs?[.size] as? NSNumber)?.intValue ?? 0
      ])
    } catch {
      emitDeviceError(eventId: eventId, code: "E_IO", message: error.localizedDescription)
    }
  }

  private func handleListFiles(eventId: Int) {
    guard let dir = deviceRootDir() else {
      emitDeviceError(eventId: eventId, code: "E_IO", message: "Documents directory unavailable.")
      return
    }

    do {
      let urls = try fileManager.contentsOfDirectory(
        at: dir,
        includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
        options: [.skipsHiddenFiles]
      )

      let files: [[String: Any]] = urls
        .filter { !$0.hasDirectoryPath }
        .sorted { $0.lastPathComponent.lowercased() < $1.lastPathComponent.lowercased() }
        .map { url in
          let values = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
          return [
            "name": url.lastPathComponent,
            "path": url.path,
            "size": values?.fileSize ?? 0,
            "lastModified": Int((values?.contentModificationDate?.timeIntervalSince1970 ?? 0) * 1000)
          ]
        }

      emitDeviceOk(eventId: eventId, extra: ["files": files, "dir": dir.path])
    } catch {
      emitDeviceError(eventId: eventId, code: "E_IO", message: error.localizedDescription)
    }
  }

  private func handleReadFile(eventId: Int, params: [String: Any]) {
    guard let fileUrl = resolveTargetFile(params: params) else {
      emitDeviceError(eventId: eventId, code: "E_PATH", message: "Provide a valid { name } or { path } within the app files directory.")
      return
    }

    guard fileManager.fileExists(atPath: fileUrl.path) else {
      emitDeviceError(eventId: eventId, code: "E_NOT_FOUND", message: "File not found.")
      return
    }

    do {
      let text = try String(contentsOf: fileUrl, encoding: .utf8)
      let attrs = try? fileManager.attributesOfItem(atPath: fileUrl.path)
      emitDeviceOk(eventId: eventId, extra: [
        "name": fileUrl.lastPathComponent,
        "path": fileUrl.path,
        "size": (attrs?[.size] as? NSNumber)?.intValue ?? 0,
        "read": text
      ])
    } catch {
      emitDeviceError(eventId: eventId, code: "E_IO", message: error.localizedDescription)
    }
  }

  private func handleWriteFile(eventId: Int, params: [String: Any]) {
    guard let fileUrl = resolveTargetFile(params: params) else {
      emitDeviceError(eventId: eventId, code: "E_PATH", message: "Provide a valid { name } or { path } within the app files directory.")
      return
    }

    let content = (params["write"] as? String) ?? ""
    let append = (params["append"] as? Bool) ?? false

    do {
      if append, let data = content.data(using: .utf8) {
        if fileManager.fileExists(atPath: fileUrl.path) {
          let handle = try FileHandle(forWritingTo: fileUrl)
          try handle.seekToEnd()
          try handle.write(contentsOf: data)
          try handle.close()
        } else {
          try content.write(to: fileUrl, atomically: true, encoding: .utf8)
        }
      } else {
        try content.write(to: fileUrl, atomically: true, encoding: .utf8)
      }

      let attrs = try? fileManager.attributesOfItem(atPath: fileUrl.path)
      emitDeviceOk(eventId: eventId, extra: [
        "name": fileUrl.lastPathComponent,
        "path": fileUrl.path,
        "size": (attrs?[.size] as? NSNumber)?.intValue ?? 0
      ])
    } catch {
      emitDeviceError(eventId: eventId, code: "E_IO", message: error.localizedDescription)
    }
  }

  private func handleGetLocation(eventId: Int, params: [String: Any]) {
    if pendingLocationEventId != nil {
      emitDeviceError(eventId: eventId, code: "E_BUSY", message: "A location request is already in progress.")
      return
    }

    pendingLocationEventId = eventId
    pendingLocationParams = params

    let manager = locationManager ?? CLLocationManager()
    locationManager = manager
    manager.delegate = self

    let enableHighAccuracy = (params["enableHighAccuracy"] as? Bool) ?? false
    manager.desiredAccuracy = enableHighAccuracy ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters

    let timeoutMs = (params["timeoutMs"] as? Int) ?? 15000
    let timeoutWork = DispatchWorkItem { [weak self] in
      guard let self, let pendingId = self.pendingLocationEventId else { return }
      self.pendingLocationEventId = nil
      self.emitDeviceError(eventId: pendingId, code: "E_TIMEOUT", message: "Timed out getting location.")
    }
    pendingLocationTimeout = timeoutWork
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(max(1000, timeoutMs)), execute: timeoutWork)

    let status = manager.authorizationStatus
    switch status {
    case .notDetermined:
      manager.requestWhenInUseAuthorization()
    case .restricted, .denied:
      finishLocationWithError(code: "E_PERMISSION", message: "Location permission denied.")
    case .authorizedAlways, .authorizedWhenInUse:
      manager.requestLocation()
    @unknown default:
      finishLocationWithError(code: "E_PERMISSION", message: "Location permission unavailable.")
    }
  }

  private func finishLocationWithError(code: String, message: String) {
    guard let pendingId = pendingLocationEventId else { return }
    pendingLocationEventId = nil
    pendingLocationTimeout?.cancel()
    pendingLocationTimeout = nil
    emitDeviceError(eventId: pendingId, code: code, message: message)
  }

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard let pendingId = pendingLocationEventId, let location = locations.last else { return }
    pendingLocationEventId = nil
    pendingLocationTimeout?.cancel()
    pendingLocationTimeout = nil

    emitDeviceOk(eventId: pendingId, extra: [
      "provider": "corelocation",
      "latitude": location.coordinate.latitude,
      "longitude": location.coordinate.longitude,
      "accuracy": location.horizontalAccuracy,
      "altitude": location.altitude,
      "speed": location.speed,
      "bearing": location.course
    ])
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    finishLocationWithError(code: "E_LOCATION", message: error.localizedDescription)
  }

  func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
    guard pendingLocationEventId != nil else { return }
    let status = manager.authorizationStatus
    switch status {
    case .authorizedAlways, .authorizedWhenInUse:
      manager.requestLocation()
    case .restricted, .denied:
      finishLocationWithError(code: "E_PERMISSION", message: "Location permission denied.")
    case .notDetermined:
      break
    @unknown default:
      finishLocationWithError(code: "E_PERMISSION", message: "Location permission unavailable.")
    }
  }

  func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
    locationManagerDidChangeAuthorization(manager)
  }

  private func handleStartShake(eventId: Int) {
    shakeListenerEventIds.insert(eventId)
    if shakeListenerEventIds.count == 1 {
      startShakeUpdates()
    }
  }

  private func handleStopShake(eventId: Int) {
    shakeListenerEventIds.remove(eventId)
    if shakeListenerEventIds.isEmpty {
      stopShakeUpdates()
    }
  }

  private func startShakeUpdates() {
    let manager = shakeMotionManager ?? CMMotionManager()
    shakeMotionManager = manager
    if !manager.isAccelerometerAvailable { return }

    lastShakeTimestampMs = 0
    manager.accelerometerUpdateInterval = 0.1
    manager.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
      guard let self, let data else { return }
      if self.shakeListenerEventIds.isEmpty { return }

      let x = data.acceleration.x
      let y = data.acceleration.y
      let z = data.acceleration.z
      let gForce = sqrt(x * x + y * y + z * z)

      let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
      let threshold = 2.7
      let minIntervalMs: Int64 = 600
      if gForce > threshold, nowMs - self.lastShakeTimestampMs > minIntervalMs {
        self.lastShakeTimestampMs = nowMs
        let payload: [String: Any] = [
          "type": "shake",
          "platform": "ios",
          "gForce": gForce,
          "timestamp": Int(nowMs)
        ]
        self.shakeListenerEventIds.forEach { id in
          self.emitDeviceEvent(eventId: id, payload: payload)
        }
      }
    }
  }

  private func stopShakeUpdates() {
    shakeMotionManager?.stopAccelerometerUpdates()
  }

  private func handleRequestNotificationPermission(eventId: Int) {
    let center = UNUserNotificationCenter.current()
    center.requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
      DispatchQueue.main.async {
        if let error {
          self.emitDeviceError(eventId: eventId, code: "E_NOTIFICATIONS", message: error.localizedDescription)
          return
        }
        if granted {
          UIApplication.shared.registerForRemoteNotifications()
        }
        self.emitDeviceOk(eventId: eventId, extra: ["granted": granted])
      }
    }
  }

  private func handleScheduleLocalNotification(eventId: Int, params: [String: Any]) {
    let id = (params["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
    let identifier = (id?.isEmpty == false) ? id! : UUID().uuidString

    let title = (params["title"] as? String) ?? "Notification"
    let body = (params["body"] as? String) ?? ""
    let delayMs = (params["delayMs"] as? Int) ?? 0
    let atMs = (params["atMs"] as? Int) ?? 0

    let nowMs = Int(Date().timeIntervalSince1970 * 1000)
    let triggerAtMs = atMs > 0 ? atMs : nowMs + max(0, delayMs)
    let seconds = max(1, Double(max(0, triggerAtMs - nowMs)) / 1000.0)

    let content = UNMutableNotificationContent()
    content.title = title
    content.body = body
    content.sound = .default

    var userInfo: [String: Any] = [
      "id": identifier,
      "title": title,
      "body": body
    ]
    if let data = params["data"] {
      userInfo["data"] = data
    }
    content.userInfo = userInfo

    let trigger = UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
    let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
    UNUserNotificationCenter.current().add(request) { error in
      DispatchQueue.main.async {
        if let error {
          self.emitDeviceError(eventId: eventId, code: "E_NOTIFICATIONS", message: error.localizedDescription)
          return
        }
        self.emitDeviceOk(eventId: eventId, extra: ["id": identifier, "scheduledAt": triggerAtMs])
      }
    }
  }

  private func handleCancelLocalNotification(eventId: Int, params: [String: Any]) {
    let id = (params["id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if id.isEmpty {
      emitDeviceError(eventId: eventId, code: "E_ID", message: "Provide { id } to cancel.")
      return
    }

    let center = UNUserNotificationCenter.current()
    center.removePendingNotificationRequests(withIdentifiers: [id])
    center.removeDeliveredNotifications(withIdentifiers: [id])
    emitDeviceOk(eventId: eventId, extra: ["id": id])
  }

  private func handleStartNotificationListener(eventId: Int, params: [String: Any]) {
    let kind = ((params["kind"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    switch kind {
    case "tap":
      notificationTapListenerEventIds.insert(eventId)
      if let pending = pendingNotificationTapPayload {
        pendingNotificationTapPayload = nil
        emitDeviceEvent(eventId: eventId, payload: pending)
      }
    case "receive":
      notificationReceiveListenerEventIds.insert(eventId)
      if !pendingNotificationReceivePayloads.isEmpty {
        let queued = pendingNotificationReceivePayloads
        pendingNotificationReceivePayloads.removeAll()
        queued.forEach { payload in
          emitDeviceEvent(eventId: eventId, payload: payload)
        }
      }
    default:
      break
    }
  }

  private func handleStopNotificationListener(eventId: Int, params: [String: Any]) {
    let kind = ((params["kind"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    switch kind {
    case "tap":
      notificationTapListenerEventIds.remove(eventId)
    case "receive":
      notificationReceiveListenerEventIds.remove(eventId)
    default:
      notificationTapListenerEventIds.remove(eventId)
      notificationReceiveListenerEventIds.remove(eventId)
    }
  }

  private func handleGetPushToken(eventId: Int) {
    if let token = apnsToken, !token.isEmpty {
      emitDeviceOk(eventId: eventId, extra: ["token": token])
      return
    }

    pendingPushTokenEventIds.append(eventId)
    UIApplication.shared.registerForRemoteNotifications()

    DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
      if let idx = self.pendingPushTokenEventIds.firstIndex(of: eventId) {
        self.pendingPushTokenEventIds.remove(at: idx)
        self.emitDeviceError(eventId: eventId, code: "E_PUSH", message: "Timed out waiting for APNs token.")
      }
    }
  }

  func setApnsToken(_ token: String) {
    apnsToken = token
    if pendingPushTokenEventIds.isEmpty { return }
    let pending = pendingPushTokenEventIds
    pendingPushTokenEventIds.removeAll()
    pending.forEach { id in
      emitDeviceOk(eventId: id, extra: ["token": token])
    }
  }

  func emitNotificationReceived(_ userInfo: [AnyHashable: Any], title: String?, body: String?) {
    var payload: [String: Any] = [
      "type": "notificationReceive",
      "platform": "ios",
      "timestamp": Int(Date().timeIntervalSince1970 * 1000)
    ]

    let mapped = userInfo.reduce(into: [String: Any]()) { acc, pair in
      acc[String(describing: pair.key)] = pair.value
    }
    payload["data"] = mapped
    if let title { payload["title"] = title }
    if let body { payload["body"] = body }

    if notificationReceiveListenerEventIds.isEmpty {
      pendingNotificationReceivePayloads.append(payload)
      return
    }

    notificationReceiveListenerEventIds.forEach { id in
      emitDeviceEvent(eventId: id, payload: payload)
    }
  }

  func emitNotificationTapped(_ userInfo: [AnyHashable: Any]) {
    var payload: [String: Any] = [
      "type": "notificationTap",
      "platform": "ios",
      "timestamp": Int(Date().timeIntervalSince1970 * 1000)
    ]

    let mapped = userInfo.reduce(into: [String: Any]()) { acc, pair in
      acc[String(describing: pair.key)] = pair.value
    }
    payload["data"] = mapped

    if notificationTapListenerEventIds.isEmpty {
      pendingNotificationTapPayload = payload
      return
    }

    notificationTapListenerEventIds.forEach { id in
      emitDeviceEvent(eventId: id, payload: payload)
    }
  }

  private func updateUI(with node: [String: Any]) {
    guard let container = containerView else { return }
    eventMap.removeAll()
    container.subviews.forEach { $0.removeFromSuperview() }

    let nativeView = renderNode(node: node)
    nativeView.frame = container.bounds
    nativeView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    container.addSubview(nativeView)
  }

  private func renderNode(node: [String: Any]) -> UIView {
    let tag = node["tag"] as? String ?? "div"
    let props = node["props"] as? [String: Any] ?? [:]
    let style = props["style"] as? [String: Any] ?? [:]

    let view: UIView
    switch tag {
    case "button":
      let button = UIButton(type: .system)
      button.setTitle(props["textContent"] as? String ?? "", for: .normal)
      applyTextStyle(to: button.titleLabel, style: style)
      if let colorHex = style["color"] as? String {
        button.setTitleColor(parseColor(colorHex), for: .normal)
      }
      view = button
    case "text":
      let label = UILabel()
      label.text = props["textContent"] as? String ?? ""
      label.numberOfLines = 0
      applyTextStyle(to: label, style: style)
      view = label
    case "input":
      let field = UITextField()
      field.borderStyle = .roundedRect
      field.placeholder = props["placeholder"] as? String
      field.text = props["value"] as? String
      if let colorHex = style["color"] as? String {
        field.textColor = parseColor(colorHex)
      }
      if let size = style["fontSize"] {
        let fontSize = CGFloat(styleInt(["fontSize": size], "fontSize", defaultValue: 16))
        field.font = .systemFont(ofSize: fontSize)
      }
      view = field
    case "select":
      let button = UIButton(type: .system)
      button.layer.borderWidth = 1
      button.layer.borderColor = UIColor.systemGray4.cgColor
      button.layer.cornerRadius = 5
      button.backgroundColor = .white
      button.contentHorizontalAlignment = .left
      button.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
      
      var options: [(label: String, value: String)] = []
      if let children = node["children"] as? [[String: Any]] {
        for child in children {
          if let childProps = child["props"] as? [String: Any],
             let textContent = childProps["textContent"] as? String {
            let value = childProps["value"] as? String ?? textContent
            options.append((label: textContent, value: value))
          }
        }
      }
      
      let selectedValue = props["value"] as? String ?? ""
      let selectedOption = options.first(where: { $0.value == selectedValue }) ?? options.first
      button.setTitle(selectedOption?.label ?? props["placeholder"] as? String ?? "Select", for: .normal)
      
      if let colorHex = style["color"] as? String {
        button.setTitleColor(parseColor(colorHex), for: .normal)
      }
      if let size = style["fontSize"] {
        let fontSize = CGFloat(styleInt(["fontSize": size], "fontSize", defaultValue: 16))
        button.titleLabel?.font = .systemFont(ofSize: fontSize)
      }
      
      // Store options and props for later use in tap handler
      button.accessibilityIdentifier = selectedValue
      button.accessibilityLabel = options.map { $0.value }.joined(separator: ",")
      button.accessibilityHint = options.map { $0.label }.joined(separator: "|")
      
      view = button
    case "image":
      let imageView = UIImageView()
      imageView.clipsToBounds = true
      imageView.contentMode = .scaleAspectFill
      if let src = props["src"] as? String {
        loadImage(from: src, into: imageView)
      }
      view = imageView
    case "activityIndicator":
      let spinner = UIActivityIndicatorView(style: .medium)
      spinner.startAnimating()
      view = spinner
    default:
      let stack = UIStackView()
      stack.axis = (style["flexDirection"] as? String) == "row" ? .horizontal : .vertical
      stack.spacing = CGFloat(styleInt(style, "gap", defaultValue: 10))
      stack.alignment = mapAlignment(style["alignItems"] as? String)
      stack.distribution = .fill
      stack.isLayoutMarginsRelativeArrangement = true
      let padding = CGFloat(styleInt(style, "padding", defaultValue: 12))
      stack.layoutMargins = UIEdgeInsets(
        top: padding,
        left: padding,
        bottom: padding,
        right: padding
      )
      view = stack
    }

    applyBaseStyle(to: view, style: style)
    bindEventsIfPresent(view: view, props: props, tag: tag)

    if let children = node["children"] as? [[String: Any]] {
      for child in children {
        let childView = renderNode(node: child)
        if let stack = view as? UIStackView {
          stack.addArrangedSubview(childView)
        } else {
          view.addSubview(childView)
        }
      }
    }

    return view
  }

  private func bindEventsIfPresent(view: UIView, props: [String: Any], tag: String) {
    guard let events = props["events"] as? [String: Any] else { return }
    let key = ObjectIdentifier(view)
    var mapped: [String: Int] = [:]
    if let clickId = events["click"] as? Int, clickId > 0 {
      mapped["click"] = clickId
    }
    if let changeId = events["change"] as? Int, changeId > 0 {
      mapped["change"] = changeId
    }
    if let doubleTapId = events["doubleTap"] as? Int, doubleTapId > 0 {
      mapped["doubleTap"] = doubleTapId
    }
    guard !mapped.isEmpty else { return }
    eventMap[key] = mapped

    if mapped["click"] != nil, let button = view as? UIButton {
      button.addTarget(self, action: #selector(handleButtonTap(_:)), for: .touchUpInside)
    }

    var singleTap: UITapGestureRecognizer? = nil
    if mapped["click"] != nil, !(view is UIButton) {
      view.isUserInteractionEnabled = true
      let tap = UITapGestureRecognizer(target: self, action: #selector(handleViewTap(_:)))
      tap.name = tag
      view.addGestureRecognizer(tap)
      singleTap = tap
    }

    if mapped["doubleTap"] != nil {
      view.isUserInteractionEnabled = true
      let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleViewDoubleTap(_:)))
      doubleTap.numberOfTapsRequired = 2
      doubleTap.name = tag
      doubleTap.cancelsTouchesInView = false
      view.addGestureRecognizer(doubleTap)
      singleTap?.require(toFail: doubleTap)
    }

    if mapped["change"] != nil, let input = view as? UITextField {
      input.addTarget(self, action: #selector(handleInputChange(_:)), for: .editingChanged)
    }
    
    if mapped["change"] != nil, tag == "select", let button = view as? UIButton {
      button.addTarget(self, action: #selector(handleSelectTap(_:)), for: .touchUpInside)
    }
  }

  @objc private func handleButtonTap(_ sender: UIButton) {
    emitEvent(for: sender, eventName: "click", tag: "button", extra: [:])
  }

  @objc private func handleSelectTap(_ sender: UIButton) {
    guard let labels = sender.accessibilityHint?.split(separator: "|").map(String.init),
          let values = sender.accessibilityLabel?.split(separator: ",").map(String.init),
          labels.count == values.count else { return }
    
    let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
    for (index, label) in labels.enumerated() {
      let action = UIAlertAction(title: label, style: .default) { _ in
        sender.setTitle(label, for: .normal)
        sender.accessibilityIdentifier = values[index]
        self.emitEvent(for: sender, eventName: "change", tag: "select", extra: ["value": values[index]])
      }
      alert.addAction(action)
    }
    let cancel = UIAlertAction(title: "Cancel", style: .cancel, handler: nil)
    alert.addAction(cancel)
    
    if let controller = rootViewController {
      controller.present(alert, animated: true, completion: nil)
    }
  }

  @objc private func handleViewTap(_ recognizer: UITapGestureRecognizer) {
    guard let view = recognizer.view else { return }
    emitEvent(for: view, eventName: "click", tag: recognizer.name ?? "view", extra: [:])
  }

  @objc private func handleViewDoubleTap(_ recognizer: UITapGestureRecognizer) {
    guard let view = recognizer.view else { return }
    emitEvent(for: view, eventName: "doubleTap", tag: recognizer.name ?? "view", extra: [:])
  }

  @objc private func handleInputChange(_ sender: UITextField) {
    emitEvent(
      for: sender,
      eventName: "change",
      tag: "input",
      extra: ["value": sender.text ?? ""]
    )
  }

  private func emitEvent(for view: UIView, eventName: String, tag: String, extra: [String: Any]) {
    let key = ObjectIdentifier(view)
    guard
      let events = eventMap[key],
      let eventId = events[eventName]
    else { return }

    var payload: [String: Any] = [
      "type": eventName,
      "tag": tag,
      "platform": "ios",
      "timestamp": Int(Date().timeIntervalSince1970 * 1000)
    ]
    extra.forEach { payload[$0.key] = $0.value }

    guard let data = try? JSONSerialization.data(withJSONObject: payload, options: []),
          let json = String(data: data, encoding: .utf8) else {
      return
    }

    let js = "window.__venjsDispatchNativeEvent && window.__venjsDispatchNativeEvent(\(eventId), \(json));"
    bridgeWebView?.evaluateJavaScript(js, completionHandler: nil)
  }

  private func loadImage(from source: String, into imageView: UIImageView) {
    let trimmed = source.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty { return }

    if trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://"),
       let url = URL(string: trimmed) {
      URLSession.shared.dataTask(with: url) { data, _, _ in
        guard let data, let image = UIImage(data: data) else { return }
        DispatchQueue.main.async {
          imageView.image = image
        }
      }.resume()
      return
    }

    if let image = UIImage(named: trimmed) {
      imageView.image = image
    }
  }

  private func applyBaseStyle(to view: UIView, style: [String: Any]) {
    if let colorHex = style["backgroundColor"] as? String {
      view.backgroundColor = parseColor(colorHex)
    }

    if style["borderRadius"] != nil || style["border-radius"] != nil {
      let radius = resolvedBorderRadius(for: view, style: style)
      view.layer.cornerRadius = radius
      view.layer.masksToBounds = radius > 0

      if hasPercentBorderRadius(style) {
        DispatchQueue.main.async { [weak view] in
          guard let view else { return }
          let deferredRadius = self.resolvedBorderRadius(for: view, style: style)
          view.layer.cornerRadius = deferredRadius
          view.layer.masksToBounds = deferredRadius > 0
        }
      }
    }

    let margin = CGFloat(styleInt(style, "margin", defaultValue: 0))
    if margin > 0 {
      view.layoutMargins = UIEdgeInsets(top: margin, left: margin, bottom: margin, right: margin)
    }
  }

  private func applyTextStyle(to label: UILabel?, style: [String: Any]) {
    guard let label else { return }
    label.font = .systemFont(ofSize: CGFloat(styleInt(style, "fontSize", defaultValue: 16)))

    if let weight = style["fontWeight"] as? String, weight.lowercased() == "bold" {
      label.font = .boldSystemFont(ofSize: CGFloat(styleInt(style, "fontSize", defaultValue: 16)))
    }

    if let colorHex = style["color"] as? String {
      label.textColor = parseColor(colorHex)
    }

    switch (style["textAlign"] as? String)?.lowercased() {
    case "center":
      label.textAlignment = .center
    case "right":
      label.textAlignment = .right
    default:
      label.textAlignment = .left
    }
  }

  private func parseColor(_ hex: String) -> UIColor {
    var value = hex.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    if value.hasPrefix("#") {
      value.removeFirst()
    }

    if value.count == 6 {
      value = "FF" + value
    }

    guard value.count == 8, let intVal = UInt64(value, radix: 16) else {
      return .clear
    }

    let a = CGFloat((intVal & 0xFF000000) >> 24) / 255.0
    let r = CGFloat((intVal & 0x00FF0000) >> 16) / 255.0
    let g = CGFloat((intVal & 0x0000FF00) >> 8) / 255.0
    let b = CGFloat(intVal & 0x000000FF) / 255.0

    return UIColor(red: r, green: g, blue: b, alpha: a)
  }

  private func styleInt(_ style: [String: Any], _ key: String, defaultValue: Int) -> Int {
    guard let raw = style[key] else { return defaultValue }
    if let number = raw as? NSNumber {
      return number.intValue
    }

    if let string = raw as? String {
      let sanitized = string.replacingOccurrences(of: "px", with: "").trimmingCharacters(in: .whitespaces)
      if let value = Int(sanitized) {
        return value
      }
      if let value = Double(sanitized) {
        return Int(value.rounded())
      }
    }

    return defaultValue
  }

  private func hasPercentBorderRadius(_ style: [String: Any]) -> Bool {
    guard let raw = style["borderRadius"] ?? style["border-radius"] else { return false }
    return String(describing: raw).trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix("%")
  }

  private func resolvedBorderRadius(for view: UIView, style: [String: Any]) -> CGFloat {
    guard let raw = style["borderRadius"] ?? style["border-radius"] else { return 0 }

    if let number = raw as? NSNumber {
      return CGFloat(number.doubleValue)
    }

    let string = String(describing: raw).trimmingCharacters(in: .whitespacesAndNewlines)
    if string.hasSuffix("%") {
      let percentRaw = string.replacingOccurrences(of: "%", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
      guard let percent = Double(percentRaw) else { return 0 }
      let base = min(view.bounds.width, view.bounds.height)
      guard base > 0 else { return 0 }
      return max(0, base * CGFloat(percent / 100.0))
    }

    let sanitized = string.replacingOccurrences(of: "px", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
    if let value = Double(sanitized) {
      return max(0, CGFloat(value))
    }

    return 0
  }

  private func mapAlignment(_ value: String?) -> UIStackView.Alignment {
    switch value?.lowercased() {
    case "center":
      return .center
    case "end", "right":
      return .trailing
    case "stretch":
      return .fill
    default:
      return .leading
    }
  }

  func webView(
    _ webView: WKWebView,
    runJavaScriptAlertPanelWithMessage message: String,
    initiatedByFrame frame: WKFrameInfo,
    completionHandler: @escaping () -> Void
  ) {
    DispatchQueue.main.async {
      let alert = UIAlertController(title: "Message", message: message, preferredStyle: .alert)
      alert.addAction(UIAlertAction(title: "OK", style: .default) { _ in
        completionHandler()
      })
      self.rootViewController?.present(alert, animated: true, completion: nil)
    }
  }
}
