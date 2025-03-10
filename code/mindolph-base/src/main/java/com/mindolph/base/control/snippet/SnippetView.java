package com.mindolph.base.control.snippet;

import com.mindolph.base.BaseView;
import com.mindolph.base.event.EventBus;
import com.mindolph.base.plugin.Plugin;
import com.mindolph.base.plugin.PluginManager;
import com.mindolph.base.plugin.SnippetHelper;
import com.mindolph.core.constant.SupportFileTypes;
import com.mindolph.core.model.NodeData;
import com.mindolph.core.model.Snippet;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import org.apache.commons.lang3.StringUtils;
import org.controlsfx.control.textfield.TextFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * @author mindolph.com@gmail.com
 * @see SnippetCell
 * @see Snippet
 */
public class SnippetView extends BaseView {

    private final static Logger log = LoggerFactory.getLogger(SnippetView.class);

    private List<BaseSnippetGroup> snippetGroups; //TBD

    @FXML
    private Accordion accordion;

    private TextField tfKeyword;

    @FXML
    private VBox vBox;

    public SnippetView() {
        super("/control/snippet_view.fxml", false);
        tfKeyword = TextFields.createClearableTextField();
        vBox.getChildren().add(0, tfKeyword);
        tfKeyword.textProperty().addListener((observableValue, s, newKeyword) -> {
            this.filter(newKeyword);
        });
        super.activeProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue) {
                this.reload(this.snippetGroups);
            }
        });

        // for switching snippets by listening file activation.
        EventBus.getIns().subscribeFileActivated(fileChange -> {
            NodeData nodeData = fileChange.newData();
            if (fileChange.oldData() != null && fileChange.newData() != null
                    && fileChange.oldData().isSameFileType(fileChange.newData())) {
                return;
            }
            if (nodeData == null) {
                this.reload(null);
                return;
            }
            switch (nodeData.getNodeType()) {
                case FOLDER -> {
                    this.reload(null);
                }
                case FILE -> {
                    if (nodeData.isPlantUml()) {
                        this.reloadForFileType(SupportFileTypes.TYPE_PLANTUML);
                    }
                    else if (nodeData.isMindMap()) {
                        this.reloadForFileType(SupportFileTypes.TYPE_MIND_MAP);
                    }
                    else {
                        this.reload(null);
                    }
                }
            }
        });

        Platform.runLater(() -> {
            accordion.requestLayout();
        });
    }

    private void reloadForFileType(String fileType) {
        Optional<Plugin> optPlugin = PluginManager.getIns().findFirstPlugin(plugin -> plugin.supportedFileTypes().contains(fileType)
                && plugin.getSnippetHelper().isPresent());
        if (optPlugin.isPresent()) {
            Optional<SnippetHelper> snippetHelper = optPlugin.get().getSnippetHelper();
            if (snippetHelper.isPresent()) {
                this.reload(snippetHelper.get().getSnippetGroups(fileType));
            }
        }
    }

    /**
     * Reload when like target file type is changed.
     *
     * @param snippetGroups
     */
    public void reload(List<BaseSnippetGroup> snippetGroups) {
        this.snippetGroups = snippetGroups;
        if (!super.getActive()) return;
        accordion.getPanes().clear();
        if (snippetGroups != null && !snippetGroups.isEmpty()) {
            for (BaseSnippetGroup snippetGroup : snippetGroups) {
//                log.debug("Load snippets for file: %s".formatted(snippetGroup.getFileType()));
                Collection<Plugin> plugins = PluginManager.getIns().findPlugins(snippetGroup.getFileType());
                for (Plugin plugin : plugins) {
                    if (plugin == null) {
                        if (log.isTraceEnabled()) log.trace("No plugin for %s".formatted(snippetGroup.getTitle()));
                        continue;
                    }
                    if (log.isTraceEnabled()) log.trace("Plugin: %s".formatted(plugin));
                    Optional<SnippetHelper> optionalSnippetHelper = plugin.getSnippetHelper();
                    if (optionalSnippetHelper.isPresent()) {
                        Optional<SnippetViewable> optView = optionalSnippetHelper.get().createView();
                        if (optView.isPresent()) {
                            SnippetViewable view = optView.get();
                            if (log.isTraceEnabled()) log.trace("Load snippet view: %s".formatted(view));
                            // TODO SHOULD have no conversion (Node)
                            TitledPane pane = new TitledPane(snippetGroup.getTitle(), (Node) view);
                            accordion.getPanes().add(pane);
                            ((Node) view).setUserData(snippetGroup.getTitle()); // be used to identify the group
                            break; // ONLY FIRST FOUND PLUGIN WILL BE USED TO HANDLE THE SNIPPETS
                        }
                    }
                }
            }
            this.filter(null);
        }
    }

    /**
     * Filter snippets by keyword, if there is no match for a group, the group will not show.
     *
     * @param keyword
     */
    public void filter(String keyword) {
        // Expand first panel in accordion.
        Platform.runLater(() -> {
            if (!accordion.getPanes().isEmpty()) {
                TitledPane first = accordion.getPanes().get(0);
                if (first != null) {
                    first.setExpanded(true);
                }
            }
        });
        // filter snippets by keywords and set to the view in accordion.
        for (TitledPane pane : this.accordion.getPanes()) {
            if (pane.getContent() instanceof SnippetViewable<?> sv) {
                SnippetViewable<Snippet> sv2 = (SnippetViewable<Snippet>) sv;
                String title = (String) ((Node) sv).getUserData();
                BaseSnippetGroup<?> snippetGroup = snippetGroupByTitle(title);
                if (snippetGroup != null) {
                    if (StringUtils.isNotEmpty(keyword)) {
                        ObservableList<Snippet> filteredSnippets;
                        List<?> filtered = snippetGroup.snippets.stream().filter(snippet -> snippet.getTitle().contains(keyword)
                                        || (snippet.getDescription() != null && snippet.getDescription().contains(keyword))
                                        || (snippet.getCode() != null && snippet.getCode().contains(keyword)))
                                .toList();
                        filteredSnippets = FXCollections.observableList(filtered.stream()
                                .map((Function<Object, Snippet>) o -> (Snippet) o).toList());
                        ((SnippetViewable<Snippet>) sv).setItems(filteredSnippets);
                    }
                    else {
                        sv2.setItems(FXCollections.observableList((List<Snippet>) snippetGroup.snippets));
                    }
                }
            }
        }

    }

    // Utils method
    private BaseSnippetGroup<?> snippetGroupByTitle(String title) {
        Optional<BaseSnippetGroup> optSnippetGroup = snippetGroups.stream()
                .filter(baseSnippetGroup -> baseSnippetGroup.getTitle().equalsIgnoreCase(title.toLowerCase())).findFirst();
        return optSnippetGroup.orElse(null);
    }


}
