extends Node
class_name VilleWebSocketClient

signal perception_received(data: Dictionary)
signal thought_received(data: Dictionary)
signal connected()
signal disconnected()

var _socket: WebSocketPeer = WebSocketPeer.new()
var _url: String = "ws://localhost:8090/ws/ville"
var _connected: bool = false

func connect_to_server(url: String = "") -> void:
	if url != "":
		_url = url
	_socket.connect_to_url(_url)

func _process(_delta: float) -> void:
	_socket.poll()
	var state = _socket.get_ready_state()

	if state == WebSocketPeer.STATE_OPEN:
		if not _connected:
			_connected = true
			_send_connect()
			connected.emit()

		while _socket.get_available_packet_count() > 0:
			var text = _socket.get_packet().get_string_from_utf8()
			_handle_message(text)

	elif state == WebSocketPeer.STATE_CLOSED:
		if _connected:
			_connected = false
			disconnected.emit()

func _send_connect() -> void:
	var msg = JSON.stringify({"type": "CONNECT", "role": "observer"})
	_socket.send_text(msg)

func _handle_message(text: String) -> void:
	var json = JSON.new()
	if json.parse(text) != OK:
		return

	var data: Dictionary = json.data
	var type: String = data.get("type", "")

	match type:
		"PERCEPTION":
			perception_received.emit(data)
		"THOUGHT":
			thought_received.emit(data)
