extends Node3D

var _ws_client: VilleWebSocketClient
var _char_controller: CharacterController
var _thought_log: RichTextLabel

func _ready() -> void:
	_ws_client = VilleWebSocketClient.new()
	add_child(_ws_client)

	_char_controller = CharacterController.new()
	add_child(_char_controller)

	_thought_log = $UI/ThoughtPanel/ThoughtLog

	_ws_client.perception_received.connect(_on_perception)
	_ws_client.thought_received.connect(_on_thought)
	_ws_client.connected.connect(func(): print("[QuarkVille] Connected to server"))
	_ws_client.disconnected.connect(func(): print("[QuarkVille] Disconnected"))

	_ws_client.connect_to_server()

func _on_perception(data: Dictionary) -> void:
	_char_controller.update_characters(data)

func _on_thought(data: Dictionary) -> void:
	var character_id: String = data.get("characterId", "?")
	var thinking: String = data.get("thinking", "")
	_thought_log.append_text("[b]%s:[/b] %s\n" % [character_id, thinking])
