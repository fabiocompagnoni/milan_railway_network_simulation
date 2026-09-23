package it.unimib.milanrailsim.gui.view;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/**
 * An indeterminate progress ring: a faint track with an accent arc turning
 * over it, in place of the dotted JavaFX indicator. Colours come from the
 * theme through the {@code loading-ring} style classes. The animation runs
 * only while the ring is visible, so a hidden banner costs nothing.
 */
final class LoadingRing extends StackPane {

	private static final double STROKE_WIDTH = 3;
	private static final double ARC_LENGTH_DEGREES = 270;
	private static final Duration TURN = Duration.seconds(1.1);

	LoadingRing(double diameter) {
		double radius = (diameter - STROKE_WIDTH) / 2;
		Circle track = new Circle(radius);
		track.getStyleClass().add("loading-ring-track");
		track.setStrokeWidth(STROKE_WIDTH);
		Arc arc = new Arc(0, 0, radius, radius, 0, ARC_LENGTH_DEGREES);
		arc.getStyleClass().add("loading-ring-arc");
		arc.setType(ArcType.OPEN);
		arc.setStrokeWidth(STROKE_WIDTH);
		arc.setStrokeLineCap(StrokeLineCap.ROUND);
		getChildren().addAll(track, arc);
		setMinSize(diameter, diameter);
		setMaxSize(diameter, diameter);

		RotateTransition turning = new RotateTransition(TURN, arc);
		turning.setByAngle(-360);
		turning.setCycleCount(Animation.INDEFINITE);
		turning.setInterpolator(Interpolator.LINEAR);
		visibleProperty().addListener((observable, was, visible) -> {
			if (visible) {
				turning.play();
			} else {
				turning.pause();
			}
		});
		turning.play();
	}
}
