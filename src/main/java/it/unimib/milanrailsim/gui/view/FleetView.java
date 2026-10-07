package it.unimib.milanrailsim.gui.view;

import it.unimib.milanrailsim.gui.app.AppModel;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

/** Rolling stock: the train catalogue with photos and figures, and the share of each type per line. */
public final class FleetView extends BorderPane {

	public FleetView(AppModel model) {
		getStyleClass().add("content");
		Label title = new Label("Materiale rotabile");
		title.getStyleClass().add("title");
		Label note = new Label("Le modifiche qui influenzano solo le prossime simulazioni, non quelle già eseguite.");
		note.getStyleClass().add("text-muted");
		setTop(new VBox(4, title, note));

		TabPane tabs = new TabPane();
		tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
		tabs.getTabs().addAll(
			new Tab("Flotta", new FleetGalleryPane(model)),
			new Tab("Assegnazione linee", new LineAssignmentsPane(model)));
		BorderPane.setMargin(tabs, new javafx.geometry.Insets(16, 0, 0, 0));
		setCenter(tabs);
	}
}
