package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import it.unimib.milanrailsim.network.FleetConfig.TrainType;
import it.unimib.milanrailsim.schedule.LineAssignments;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Gallery of train types with a detail card; editing, photo upload and deletion happen here. */
final class FleetGalleryPane extends VBox {

	private static final List<String> PHOTO_EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");
	private static final long PHOTO_MAX_BYTES = 10_000_000;

	private final AppModel model;
	private final FlowPane gallery = new FlowPane(12, 12);
	private final StackPane detail = new StackPane();
	private String selectedId;

	FleetGalleryPane(AppModel model) {
		this.model = model;
		setSpacing(16);
		setPadding(new Insets(16, 0, 0, 0));
		ScrollPane scroll = new ScrollPane(gallery);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("plain-scroll");
		scroll.setPrefHeight(230);
		Button add = new Button("+ Nuovo tipo");
		add.setOnAction(event -> showEditor(null));
		HBox header = new HBox(add);
		header.setAlignment(Pos.CENTER_RIGHT);
		getChildren().addAll(header, scroll, detail);
		VBox.setVgrow(detail, Priority.ALWAYS);
		model.fleet().addListener(observable -> rebuild());
		rebuild();
	}

	private void rebuild() {
		gallery.getChildren().clear();
		List<TrainType> types = model.fleet().get().types();
		types.forEach(type -> gallery.getChildren().add(card(type)));
		if (types.isEmpty()) {
			Label empty = new Label("Nessun tipo di treno definito. Aggiungi il primo tipo per iniziare.");
			empty.getStyleClass().add("text-muted");
			gallery.getChildren().add(empty);
			detail.getChildren().clear();
			return;
		}
		if (selectedId == null || types.stream().noneMatch(type -> type.id().equals(selectedId))) {
			selectedId = types.getFirst().id();
		}
		showDetail(model.fleet().get().type(selectedId));
	}

	private Node card(TrainType type) {
		ImageView image = photoView(type.id(), 180, 100);
		Label name = new Label(type.name());
		name.getStyleClass().add("card-title");
		Label figures = new Label((int) type.vmaxKmh() + " km/h · " + type.seats() + " posti");
		figures.getStyleClass().add("text-muted");
		VBox card = new VBox(6, image, name, figures);
		card.getStyleClass().add("card");
		card.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), type.id().equals(selectedId));
		card.setOnMouseClicked(event -> {
			selectedId = type.id();
			rebuild();
		});
		return card;
	}

	private void showDetail(TrainType type) {
		ImageView image = photoView(type.id(), 320, 180);
		Button photo = new Button("Carica foto");
		photo.setOnAction(event -> uploadPhoto(type.id()));
		Button edit = new Button("Modifica");
		edit.setOnAction(event -> showEditor(type));
		Button delete = new Button("Elimina");
		delete.setOnAction(event -> delete(type));
		HBox actions = new HBox(8, photo, edit, delete);

		List<String> citations = new ArrayList<>();
		VBox figures = new VBox(6,
			figure("Lunghezza", String.format("%.1f m", type.lengthMeters()), type, "length", citations),
			figure("Posti", String.valueOf(type.seats()), type, "seats", citations),
			figure("Velocità massima", (int) type.vmaxKmh() + " km/h", type, "vmax", citations),
			figure("Accelerazione", String.format("%.2f m/s²", type.accelerationMps2()), type, "acceleration", citations),
			figure("Decelerazione", String.format("%.2f m/s²", type.decelerationMps2()), type, "deceleration", citations),
			figure("Trazione", type.traction().label(), type, "traction", citations),
			figure("Linee dove circola", linesUsing(type.id()), type, "lines", citations));
		figures.getChildren().add(sourcesNote(citations, type));

		Label title = new Label(type.name());
		title.getStyleClass().add("section-title");
		Label id = new Label("id " + type.id());
		id.getStyleClass().add("text-muted");
		VBox text = new VBox(10, new HBox(12, title, id), figures, actions);
		HBox.setHgrow(text, Priority.ALWAYS);
		HBox body = new HBox(24, image, text);
		body.getStyleClass().add("panel");
		detail.getChildren().setAll(body);
	}

	/** A figure with a numbered citation, collected in {@code citations} in first-use order. */
	private Node figure(String label, String value, TrainType type, String field, List<String> citations) {
		Label key = new Label(label);
		key.getStyleClass().add("text-muted");
		key.setMinWidth(150);
		Label text = new Label(value);
		text.getStyleClass().add("metric");
		HBox row = new HBox(6, key, text);
		String source = type.sources().get(field);
		if (source != null) {
			if (!citations.contains(source)) {
				citations.add(source);
			}
			Label reference = new Label(String.valueOf(citations.indexOf(source) + 1));
			reference.getStyleClass().add("citation");
			row.getChildren().add(reference);
		} else if (type.isEstimated(field)) {
			Label estimate = new Label("stima");
			estimate.getStyleClass().add("estimate");
			row.getChildren().add(estimate);
		}
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private Node sourcesNote(List<String> citations, TrainType type) {
		VBox note = new VBox(2);
		note.setPadding(new Insets(8, 0, 0, 0));
		for (int i = 0; i < citations.size(); i++) {
			Label line = new Label((i + 1) + ". " + citations.get(i));
			line.getStyleClass().add("footnote");
			line.setWrapText(true);
			note.getChildren().add(line);
		}
		if (type.estimated().stream().anyMatch(field -> !type.sources().containsKey(field))) {
			Label estimate = new Label("I valori indicati come stima non hanno una fonte pubblicata.");
			estimate.getStyleClass().add("footnote");
			note.getChildren().add(estimate);
		}
		return note;
	}

	private String linesUsing(String typeId) {
		LineAssignments assignments = model.assignments().get();
		String lines = assignments.byLine().entrySet().stream()
			.filter(entry -> entry.getValue().stream().anyMatch(share -> share.vehicleTypeId().equals(typeId)))
			.map(Map.Entry::getKey).sorted().reduce((a, b) -> a + " " + b).orElse("");
		if (LineAssignments.REGIONAL_DEFAULT_TYPE.equals(typeId)) {
			lines += (lines.isEmpty() ? "" : " ") + "+ regionali non assegnate";
		}
		return lines.isEmpty() ? "nessuna" : lines;
	}

	private void showEditor(TrainType existing) {
		TrainTypeEditor editor = new TrainTypeEditor(model.fleet().get(), existing, saved -> {
			model.saveFleet(model.fleet().get().with(saved));
			selectedId = saved.id();
			rebuild();
		}, this::rebuild);
		detail.getChildren().setAll(editor);
	}

	private void delete(TrainType type) {
		List<String> users = model.assignments().get().byLine().entrySet().stream()
			.filter(entry -> entry.getValue().stream().anyMatch(share -> share.vehicleTypeId().equals(type.id())))
			.map(Map.Entry::getKey).sorted().toList();
		if (!users.isEmpty()) {
			new Alert(Alert.AlertType.WARNING, "Impossibile eliminare " + type.name() + ": è assegnato a "
				+ String.join(", ", users) + ". Rimuovilo prima dalle linee.").showAndWait();
			return;
		}
		Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
			"Eliminare " + type.name() + "? Verrà rimossa anche la foto caricata.", ButtonType.OK, ButtonType.CANCEL);
		if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
			photoOf(type.id()).ifPresent(photo -> {
				try {
					Files.delete(photo);
				} catch (IOException e) {
					throw new UncheckedIOException("Cannot delete " + photo, e);
				}
			});
			model.saveFleet(model.fleet().get().without(type.id()));
		}
	}

	private void uploadPhoto(String typeId) {
		FileChooser chooser = new FileChooser();
		chooser.setTitle("Foto del treno");
		chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Immagini", "*.jpg", "*.jpeg", "*.png", "*.webp"));
		java.io.File chosen = chooser.showOpenDialog(getScene().getWindow());
		if (chosen == null) {
			return;
		}
		Path source = chosen.toPath();
		String extension = extension(source);
		if (!PHOTO_EXTENSIONS.contains(extension)) {
			new Alert(Alert.AlertType.ERROR, "Immagine non caricata. Formato non supportato: usa JPG, PNG o WEBP.").showAndWait();
			return;
		}
		try {
			if (Files.size(source) > PHOTO_MAX_BYTES) {
				new Alert(Alert.AlertType.ERROR, "Immagine non caricata. File troppo grande (limite 10 MB).").showAndWait();
				return;
			}
			Files.createDirectories(model.paths().fleetPhotos());
			photoOf(typeId).ifPresent(previous -> {
				try {
					Files.delete(previous);
				} catch (IOException e) {
					throw new UncheckedIOException("Cannot replace " + previous, e);
				}
			});
			Files.copy(source, model.paths().fleetPhotos().resolve(typeId + "." + extension), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot copy photo " + source, e);
		}
		rebuild();
	}

	private ImageView photoView(String typeId, double width, double height) {
		ImageView view = new ImageView();
		view.setFitWidth(width);
		view.setFitHeight(height);
		view.setPreserveRatio(true);
		photoOf(typeId).ifPresent(photo -> view.setImage(new Image(photo.toUri().toString(), width, height, true, true)));
		if (view.getImage() == null) {
			view.getStyleClass().add("photo-placeholder");
		}
		return view;
	}

	private Optional<Path> photoOf(String typeId) {
		Path dir = model.paths().fleetPhotos();
		if (!Files.isDirectory(dir)) {
			return Optional.empty();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(file -> file.getFileName().toString().startsWith(typeId + ".")).findFirst();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot list " + dir, e);
		}
	}

	private static String extension(Path file) {
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
	}
}
