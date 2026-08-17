extends Node3D
class_name CharacterController

const LERP_SPEED: float = 5.0
const COLORS: Array[Color] = [
	Color(0.2, 0.6, 0.9),
	Color(0.9, 0.3, 0.3),
	Color(0.3, 0.8, 0.3),
	Color(0.9, 0.7, 0.2),
]

var _characters: Dictionary = {}
var _color_index: int = 0

func update_characters(data: Dictionary) -> void:
	var chars: Array = data.get("characters", [])
	if chars.is_empty():
		return

	for c in chars:
		var id: String = c.get("id", "")
		if id == "":
			continue

		if not _characters.has(id):
			_create_character(id)

		var node: Node3D = _characters[id]["node"]
		var target_pos = Vector3(c.get("x", 0.0), 0.5, c.get("y", 0.0))
		_characters[id]["target"] = target_pos

		var needs: Dictionary = c.get("needs", {})
		_update_need_bars(id, needs)

		var dialogue: String = c.get("lastDialogue", "")
		if dialogue != "" and dialogue != _characters[id].get("last_dialogue", ""):
			_characters[id]["last_dialogue"] = dialogue
			_show_dialogue(id, dialogue)

func _process(delta: float) -> void:
	for id in _characters:
		var info: Dictionary = _characters[id]
		var node: Node3D = info["node"]
		var target: Vector3 = info.get("target", node.position)
		node.position = node.position.lerp(target, delta * LERP_SPEED)

func _create_character(id: String) -> void:
	var body = CSGCylinder3D.new()
	body.radius = 0.3
	body.height = 1.0
	body.position = Vector3(0, 0.5, 0)
	var mat = StandardMaterial3D.new()
	mat.albedo_color = COLORS[_color_index % COLORS.size()]
	_color_index += 1
	body.material = mat

	var root = Node3D.new()
	root.name = id
	root.add_child(body)
	add_child(root)

	var label = Label3D.new()
	label.name = "NameLabel"
	label.text = id
	label.position = Vector3(0, 1.8, 0)
	label.font_size = 32
	label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	root.add_child(label)

	var dialogue_label = Label3D.new()
	dialogue_label.name = "DialogueLabel"
	dialogue_label.text = ""
	dialogue_label.position = Vector3(0, 2.2, 0)
	dialogue_label.font_size = 24
	dialogue_label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	dialogue_label.modulate = Color(1, 1, 0.8)
	root.add_child(dialogue_label)

	_characters[id] = {
		"node": root,
		"target": root.position,
		"last_dialogue": "",
		"dialogue_timer": 0.0,
	}

func _update_need_bars(id: String, needs: Dictionary) -> void:
	pass

func _show_dialogue(id: String, text: String) -> void:
	var node: Node3D = _characters[id]["node"]
	var label: Label3D = node.get_node("DialogueLabel")
	label.text = text
	_characters[id]["dialogue_timer"] = 5.0

	get_tree().create_timer(5.0).timeout.connect(func():
		if _characters.has(id):
			var l: Label3D = _characters[id]["node"].get_node("DialogueLabel")
			if l.text == text:
				l.text = ""
	)
