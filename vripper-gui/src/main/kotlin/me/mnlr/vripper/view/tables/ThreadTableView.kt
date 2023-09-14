package me.mnlr.vripper.view.tables

import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.control.*
import javafx.scene.image.ImageView
import me.mnlr.vripper.controller.ThreadController
import me.mnlr.vripper.event.Event
import me.mnlr.vripper.event.EventBus
import me.mnlr.vripper.model.ThreadModel
import me.mnlr.vripper.view.FxScheduler
import tornadofx.*

class ThreadTableView : View() {

    private val threadController: ThreadController by inject()
    private val eventBus: EventBus by di()

    lateinit var tableView: TableView<ThreadModel>

    private var items: ObservableList<ThreadModel> = FXCollections.observableArrayList()

    init {
        titleProperty.bind(items.sizeProperty.map {
            if (it.toLong() > 0) {
                "Threads (${it.toLong()})"
            } else {
                "Threads"
            }
        })
        items.addAll(threadController.findAll())

        eventBus
            .flux()
            .filter { it!!.kind == Event.Kind.THREAD_UPDATE }
            .map { threadController.find(it.data as Long) }
            .filter { it.isPresent }
            .map { it.get() }
            .publishOn(FxScheduler)
            .subscribe {
                val find =
                    items.find { threadModel -> threadModel.threadId == it.threadId }
                if (find != null) {
                    find.apply {
                        total = it.total
                    }
                } else {
                    items.add(it)
                }
            }

        eventBus
            .flux()
            .filter { it.kind == Event.Kind.THREAD_REMOVE }
            .publishOn(FxScheduler)
            .subscribe { event ->
                tableView.items.removeIf { it.threadId == event.data as String }
            }

        eventBus
            .flux()
            .filter { it.kind == Event.Kind.THREAD_CLEAR }
            .publishOn(FxScheduler)
            .subscribe {
                tableView.items.clear()
            }
    }

    override fun onDock() {
        tableView.prefHeightProperty().bind(root.heightProperty())
    }

    override val root = vbox {
        tableView = tableview(items) {
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            setRowFactory {
                val tableRow = TableRow<ThreadModel>()

                tableRow.setOnMouseClicked {
                    if (it.clickCount == 2 && tableRow.item != null) {
                        selectPosts(tableRow.item.threadId)
                    }
                }

                val contextMenu = ContextMenu()
                val selectItem = MenuItem("Select posts")
                selectItem.setOnAction {
                    selectPosts(tableRow.item.threadId)
                }
                val selectIcon = ImageView("popup.png")
                selectIcon.fitWidth = 18.0
                selectIcon.fitHeight = 18.0
                selectItem.graphic = selectIcon

                val deleteItem = MenuItem("Delete")
                deleteItem.setOnAction {
                    deleteSelected()
                }
                val deleteIcon = ImageView("trash.png")
                deleteIcon.fitWidth = 18.0
                deleteIcon.fitHeight = 18.0
                deleteItem.graphic = deleteIcon

                contextMenu.items.addAll(selectItem, SeparatorMenuItem(), deleteItem)
                tableRow.contextMenuProperty().bind(tableRow.emptyProperty()
                    .map { empty -> if (empty) null else contextMenu })
                tableRow
            }
            column("URL", ThreadModel::linkProperty) {
                prefWidth = 350.0
            }
            column("Count", ThreadModel::totalProperty)
        }
    }

    fun deleteSelected() {
        val threadIdList = tableView.selectionModel.selectedItems.map { it.threadId }
        confirm(
            "Clean threads",
            "Confirm removal of ${threadIdList.size} thread${if (threadIdList.size > 1) "s" else ""}",
            ButtonType.YES,
            ButtonType.NO
        ) {
            threadController.delete(threadIdList)
        }
    }

    private fun selectPosts(threadId: String) {
        find<ThreadSelectionTableView>(mapOf(ThreadSelectionTableView::threadId to threadId)).openModal()

    }
}