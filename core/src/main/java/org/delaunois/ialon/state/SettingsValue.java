package org.delaunois.ialon.state;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.DefaultRangedValueModel;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.VAlignment;
import com.simsilica.lemur.core.VersionedReference;

import org.delaunois.ialon.ui.Slider;

import java.util.function.Function;

import static org.delaunois.ialon.Ialon.IALON_STYLE;

public class SettingsValue {

    private final String title;
    private final double min;
    private final double max;
    private final double value;
    private final Camera cam;
    private final Function<Double, String> labelValueFunction;
    private VersionedReference<Double> sliderRef;
    private Label label;

    public SettingsValue(String title, Camera cam, double min, double max, double value) {
        this(title, cam, min, max, value, String::valueOf);
    }

    public SettingsValue(String title, Camera cam, double min, double max, double value, Function<Double, String> labelValueFunction) {
        this.title = title;
        this.cam = cam;
        this.min = min;
        this.max = max;
        this.value = value;
        this.labelValueFunction = labelValueFunction;
    }

    public void addToContainer(Container container, int position) {
        float vw = cam.getWidth() / 100f;
        float vh = cam.getHeight() / 100f;
        Label titleLabel = new Label(title);
        titleLabel.setFontSize(4 * vh);
        titleLabel.setPreferredSize(new Vector3f(20  * vw, 8 * vh, 0));
        titleLabel.setTextVAlignment(VAlignment.Center);
        container.addChild(titleLabel, position, 0);

        Slider slider = new Slider(IALON_STYLE);
        slider.setPreferredSize(new Vector3f(50  * vw, 8 * vh, 0));
        slider.setModel(new DefaultRangedValueModel(min, max, value));
        slider.setLocalTranslation(0, 0, 4f);
        slider.setInsets(new Insets3f(0, vw, 0, vw));
        sliderRef = container.addChild(slider, position, 1).getModel().createReference();

        label = new Label(labelValueFunction.apply(value));
        label.setFontSize(4 * vh);
        label.setPreferredSize(new Vector3f(8 * vw, 8 * vh, 0));
        label.setTextVAlignment(VAlignment.Center);
        container.addChild(label, position, 2);
    }

    public void update() {
        if (sliderRef.update()) {
            label.setText(labelValueFunction.apply(sliderRef.get()));
        }
    }

    public double getValue() {
        return sliderRef.get();
    }

}
