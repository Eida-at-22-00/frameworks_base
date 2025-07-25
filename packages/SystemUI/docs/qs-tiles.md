# Quick Settings Tiles (almost all there is to know about them)

[TOC]

## About this document

This document is a more or less comprehensive summary of the state and infrastructure used by Quick
Settings tiles. It provides descriptions about the lifecycle of a tile, how to create new tiles and
how SystemUI manages and displays tiles, among other topics.

A lot of the tile backend architecture is in the process of being replaced by a new architecture in
order to align with the
[recommended architecture](https://developer.android.com/topic/architecture#recommended-app-arch).

While we are in the process of migrating, this document will try to provide a comprehensive
overview of the current architecture as well as the new one. The sections documenting the new
architecture are marked with the tag [NEW-ARCH].

## What are Quick Settings Tiles?

Quick Settings (from now on, QS) is the expanded panel that contains shortcuts for the user to
toggle many settings. This is opened by expanding the notification drawer twice (or once when phone
is locked). Quick Quick Settings (QQS) is the smaller panel that appears on top of the notifications
before expanding twice and contains some of the toggles with no secondary line.

Each of these toggles that appear either in QS or QQS are called Quick Settings Tiles (or tiles for
short). They allow the user to enable or disable settings quickly and sometimes provides access to
more comprehensive settings pages.

The following image shows QQS on the left and QS on the right, with the tiles highlighted.

![QQS on the left, QS on the right](QS-QQS.png)

QS Tiles usually depend on one or more Controllers that bind the tile with the necessary service.
Controllers are obtained by the backend and used for communication between the user and the device.

### A note on multi-user support

All the classes described in this document that live inside SystemUI are only instantiated in the
process of user 0. The different controllers that back the QS Tiles (also instantiated just in user
0) are user aware and provide an illusion of different instances for different users.

For an example on this,
see [`RotationLockController`](/packages/SystemUI/src/com/android/systemui/statusbar/policy/RotationLockControllerImpl.java).
This controller for the `RotationLockTile` listens to changes in all users.

## What are tiles made of?

### Tile backend

QS Tiles are composed of the following backend classes.

* [`QSTile`](/packages/SystemUI/plugin/src/com/android/systemui/plugins/qs/QSTile.java): Interface
  providing common behavior for all Tiles. This class also contains some useful utility classes
  needed for the tiles.
    * `Icon`: Defines the basic interface for an icon as used by the tiles.
    * `State`: Encapsulates the state of the Tile in order to communicate between the backend and
      the UI.
* [`QSTileImpl`](/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileImpl.java): Abstract
  implementation of `QSTile`, providing basic common behavior for all tiles. Also implements
  extensions for different types of `Icon`. All tiles currently defined in SystemUI subclass from
  this implementation.
* [`SystemUI/src/com/android/systemui/qs/tiles`](/packages/SystemUI/src/com/android/systemui/qs/tiles):
  Each tile from SystemUI is defined here by a class that extends `QSTileImpl`. These
  implementations connect to corresponding controllers. The controllers serve two purposes:
    * track the state of the device and notify the tile when a change has occurred (for example,
      bluetooth connected to a device)
    * accept actions from the tiles to modify the state of the phone (for example, enablind and
      disabling wifi).
* [`CustomTile`](/packages/SystemUI/src/com/android/systemui/qs/external/CustomTile.java):
  Equivalent to the tiles in the previous item, but used for 3rd party tiles. In depth information
  to be found in [`CustomTile`](#customtile)

All the elements in SystemUI that work with tiles operate on `QSTile` or the interfaces defined in
it. However, all the current implementations of tiles in SystemUI subclass from `QSTileImpl`, as it
takes care of many common situations. Throughout this document, we will focus on `QSTileImpl` as
examples of tiles.

The interfaces in `QSTile` as well as other interfaces described in this document can be used to
implement plugins to add additional tiles or different behavior. For more information,
see [plugins.md](plugins.md)


#### [NEW-ARCH] Tile backend
Instead of `QSTileImpl` the tile backend is made of a view model called `QSTileViewModelImpl`,
which in turn is composed of 3 interfaces:

* [`QSTileDataInteractor`](/packages/SystemUI/src/com/android/systemui/qs/tiles/base/interactor/QSTileDataInteractor.kt)
is responsible for providing the data for the tile. It is responsible for fetching the state of
the tile from the source of truth and providing that information to the tile. Typically the data
interactor will read system state from a repository or a controller and provide a flow of
domain-specific data model.

* [`QSTileUserActionInteractor`](/packages/SystemUI/src/com/android/systemui/qs/tiles/base/interactor/QSTileUserActionInteractor.kt) is responsible for handling the user actions on the tile.
This interactor decides what should happen when the user clicks, long clicks on the tile.

* [`QSTileDataToStateMapper`](/packages/SystemUI/src/com/android/systemui/qs/tiles/base/mapper/QSTileMapper.kt)
is responsible for mapping the data received from the data interactor to a state that the view
model can use to update the UI.

At the time being, the `QSTileViewModel`s are adapted to `QSTile`. This conversion is done by
`QSTileViewModelAdapter`.

#### Tile State

Each tile has an associated `State` object that is used to communicate information to the
corresponding view. The base class `State` has (among others) the following fields:

* **`state`**: one of `Tile#STATE_UNAVAILABLE`, `Tile#STATE_ACTIVE`, `Tile#STATE_INACTIVE`.
* **`icon`**; icon to display. It may depend on the current state.
* **`label`**: usually the name of the tile.
* **`secondaryLabel`**: text to display in a second line. Usually extra state information.
* **`contentDescription`**
* **`expandedAccessibilityClassName`**: usually `Switch.class.getName()` for boolean Tiles. This
  will make screen readers read the current state of the tile as well as the new state when it's
  toggled. For this, the Tile has to use `BooleanState`.
* **`handlesLongClick`**: whether the Tile will handle long click. If it won't, it should be set
  to `false` so it will not be announced for accessibility.

Setting any of these fields during `QSTileImpl#handleUpdateState` will update the UI after it.

Additionally. `BooleanState` has a `value` boolean field that usually would be set
to `state == Tile#STATE_ACTIVE`. This is used by accessibility services along
with `expandedAccessibilityClassName`.

#### [NEW-ARCH] Tile State
In the new architecture, the mapper generates
[`QSTileState`](packages/SystemUI/src/com/android/systemui/qs/tiles/viewmodel/QSTileState.kt),
which again is converted to the old state by `QSTileViewModelAdapter`.

#### SystemUI tiles

Each tile defined in SystemUI extends `QSTileImpl`. This abstract class implements some common
functions and leaves others to be implemented by each tile, in particular those that determine how
to handle different events (refresh, click, etc.).

For more information on how to implement a tile in SystemUI,
see [Implementing a SystemUI tile](#implementing-a-systemui-tile).

As mentioned before, when the [NEW-ARCH] migration is complete, we will remove the `QSTileImpl`
and `QSTileViewModelAdapter` and directly use`QSTileViewModelImpl`.

### Tile views

Each Tile has a couple of associated views for displaying it in QS and QQS. These views are updated
after the backend updates the `State` using `QSTileImpl#handleUpdateState`.

* **[`QSTileView`](/packages/SystemUI/plugin/src/com/android/systemui/plugins/qs/QSTileView.java)**:
  Abstract class that provides basic Tile functionality. These allows
  external [Factories](#qsfactory) to create Tiles.
* **[`QSTileViewImpl`](/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.java)**:
  Implementation of `QSTileView`. It takes care of the following:
    * Holding the icon
    * Background color and shape
    * Ripple
    * Click listening
    * Labels
* **[`QSIconView`](/packages/SystemUI/plugin/src/com/android/systemui/plugins/qs/QSIconView.java)**
* **[`QSIconViewImpl`](/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSIconViewImpl.java)**

#### QSIconView and QSIconViewImpl

`QSIconView` is an interface that define the basic actions that icons have to respond to. Its base
implementation in SystemUI is `QSIconViewImpl` and it and its subclasses are used by all QS tiles.

This `ViewGroup` is a container for the icon used in each tile. It has methods to apply the
current `State` of the tile, modifying the icon (color and animations). Classes that inherit from
this can add other details that are modified when the `State` changes.

Each `QSTileImpl` can specify that they use a particular implementation of this class when creating
an icon.

### How are the backend and the views related?

The backend of the tiles (all the implementations of `QSTileImpl`) communicate with the views by
using a `State`. The backend populates the state, and then the view maps the state to a visual
representation.

It's important to notice that the state of the tile (internal or visual) is not directly modified by
a user action like clicking on the tile. Instead, acting on a tile produces internal state changes
on the device, and those trigger the changes on the tile state and UI.

When a container for tiles (`QuickQSPanel` or `QSPanel`) has to display tiles, they create
a [`TileRecord`](/packages/SystemUI/src/com/android/systemui/qs/QSPanel.java). This associates the
corresponding `QSTile` with its `QSTileView`, doing the following:

* Create the corresponding `QSTileView` to display in that container.
* Create a callback for `QSTile` to call when its state changes. Note that a single tile will
  normally have up to two callbacks: one for QS and one for QQS.

#### Life of a tile click

This is a brief run-down of what happens when a user clicks on a tile. Internal changes on the
device (for example, changes from Settings) will trigger this process starting in step 5.

Step | Legacy Tiles | [NEW-ARCH] Tiles
-------|-------|---------
1 | User clicks on tile. | Same as legacy tiles.
2 | `QSTileViewImpl#onClickListener` | Same as legacy tiles.
3 | `QSTile#click` | Same as legacy tiles.
4| `QSTileImpl#handleClick` | `QSTileUserActionInteractor#handleInput`
5| State in the device changes. This is normally outside of SystemUI's control. Controller receives a callback (or `Intent`) indicating the change in the device. | Same as legacy tiles.
6 |  `QSTile#refreshState`and `QSTileImpl#handleRefreshState` | `QSTileDataInteractor#tileData()`
7| `QSTileImpl#handleUpdateState` is called to update the state with the new information. This information can be obtained both from the `Object` passed to `refreshState` as well as from the controller. | The data that was generated by the data interactor is read by the `QSTileViewModelImpl.state` flow which calls `QSTileMapper#map` on the data to generate a new `QSTileState`.
8|  If the state has changed (in at least one element `QSTileImpl#handleStateChanged` is called. This will trigger a call to all the associated `QSTile.Callback#onStateChanged`, passing the new `State`. | The newly mapped QSTileState is read by the `QSTileViewModelAdapter` which then maps it to a legacy `State`. Similarly to the legacy tiles, the new state is compared to the old one and if there is a difference, `QSTile.Callback#onStateChanged` is called for all the associated callbacks.
9 | `QSTileView#onStateChanged` is called and this calls `QSTileView#handleStateChanged`. This method maps the state updating tile color and label, and calling `QSIconView.setIcon` | Same as legacy tiles.

## Third party tiles (TileService)

A third party tile is any Quick Settings tile that is provided by an app (that's not SystemUI).
This is implemented by developers
subclassing [`TileService`](/core/java/android/service/quicksettings/TileService.java) and
interacting with its API.

### API classes

The classes that define the public API are
in [core/java/android/service/quicksettings](/core/java/android/service/quicksettings).

#### Tile

Parcelable class used to communicate information about the state between the external app and
SystemUI. The class supports the following fields:

* Label
* Subtitle
* Icon
* State (`Tile#STATE_ACTIVE`, `Tile#STATE_INACTIVE`, `Tile#STATE_UNAVAILABLE`)
* Content description

Additionally, it provides a method to notify SystemUI that the information may have changed and the
tile should be refreshed.

#### TileService

This is an abstract Service that needs to be implemented by the developer. The Service manifest must
have the permission `android.permission.BIND_QUICK_SETTINGS_TILE` and must respond to the
action `android.service.quicksettings.action.QS_TILE`. This will allow SystemUI to find the
available tiles and display them to the user.

The implementer is responsible for creating the methods that will respond to the following calls
from SystemUI:

* **`onTileAdded`**: called when the tile is added to QS.
* **`onTileRemoved`**: called when the tile is removed from QS.
* **`onStartListening`**: called when QS is opened and the tile is showing. This marks the start of
the window when calling `getQSTile` is safe and will provide the correct object.
* **`onStopListening`**: called when QS is closed or the tile is no longer visible by the user.
This marks the end of the window described in `onStartListening`.
* **`onClick`**: called when the user clicks on the tile.

Additionally, the following final methods are provided:

* ```java
  public final Tile getQsTile()
  ```

  Provides the tile object that can be modified. This should only be called in the window
  between `onStartListening` and `onStopListening`.

* ```java
  public final boolean isLocked()

  public final boolean isSecure()
  ```

  Provide information about the secure state of the device. This can be used by the tile to accept
  or reject actions on the tile.

* ```java
  public final void unlockAndRun(Runnable)
  ```

  May prompt the user to unlock the device if locked. Once the device is unlocked, it runs the
  given `Runnable`.

* ```java
  public final void showDialog(Dialog)
  ```

  Shows the provided dialog.

##### Binding

When the Service is bound, a callback Binder is provided by SystemUI for all the callbacks, as well
as an identifier token (`Binder`). This token is used in the callbacks to identify
this `TileService` and match it to the corresponding tile.

The tiles are bound once immediately on creation. After that, the tile is bound whenever it should
start listening. When the panels are closed, and the tile is set to stop listening, it will be
unbound after a delay of `TileServiceManager#UNBIND_DELAY` (30s), if it's not set to listening
again.

##### Active tile

A `TileService` can be declared as an active tile by adding specific meta-data to its manifest (
see [TileService#META_DATA_ACTIVE_TILE](https://developer.android.com/reference/android/service/quicksettings/TileService#META_DATA_ACTIVE_TILE)).
In this case, it won't receive a call of `onStartListening` when QS is opened. Instead, the tile
must request listening status by making a call to `TileService#requestListeningState` with its
component name. This will initiate a window that will last until the tile is updated.

The tile will also be granted listening status if it's clicked by the user.

### SystemUI classes

The following sections describe the classes that live in SystemUI to support third party tiles.
These classes live
in [SystemUI/src/com/android/systemui/qs/external](/packages/SystemUI/src/com/android/systemui/qs/external/)

#### CustomTile

This class is an subclass of `QSTileImpl` to be used with third party tiles. It provides similar
behavior to SystemUI tiles as well as handling exclusive behavior like lifting default icons and
labels from the application manifest.

#### TileServices

This class is the central controller for all tile services that are currently in Quick Settings as
well as provides the support for starting new ones. It is also an implementation of the `Binder`
that receives all calls from current `TileService` components and dispatches them to SystemUI or the
corresponding `CustomTile`.

Whenever a binder call is made to this class, it matches the corresponding token assigned to
the `TileService` with the `ComponentName` and verifies that the call comes from the right UID to
prevent spoofing.

As this class is the only one that's aware of every `TileService` that's currently bound, it is also
in charge of requesting some to be unbound whenever there is a low memory situation.

#### TileLifecycleManager

This class is in charge of binding and unbinding to a particular `TileService` when necessary, as
well as sending the corresponding binder calls. It does not decide whether the tile should be bound
or unbound, unless it's requested to process a message. It additionally handles errors in
the `Binder` as well as changes in the corresponding component (like updates and enable/disable).

The class has a queue that stores requests while the service is not bound, to be processed as soon
as the service is bound.

Each `TileService` gets assigned an exclusive `TileLifecycleManager` when its corresponding tile is
added to the set of current ones and kept as long as the tile is available to the user.

#### TileServiceManager

Each instance of this class is an intermediary between the `TileServices` controller and
a `TileLifecycleManager` corresponding to a particular `TileService`.

This class handles management of the service, including:

* Deciding when to bind and unbind, requesting it to the `TileLifecycleManager`.
* Relaying messages to the `TileService` through the `TileLifecycleManager`.
* Determining the service's bind priority (to deal with OOM situations).
* Detecting when the package/component has been removed in order to remove the tile and references
  to it.

## How are tiles created/instantiated?

This section describes the classes that aid in the creation of each tile as well as the complete
lifecycle of a tile. The current system makes use of flows to propagate information downstream.

First we describe three important interfaces/classes.

### TileSpecRepository (and UserTileSpecRepository)

These classes keep track of the current tiles for each user, as a list of Tile specs. While the
device is running, this is the source of truth of tiles for that user.

The list is persisted to `Settings.Secure` every time it changes so it will be available upon
restart or backup. In particular, any changes in the secure setting while this repository is
tracking the list of tiles will be reverted.

The class provides a `Flow<List<TileSpec>>` for each user that can be collected to keep track of the
current list of tiles.

#### Tile specs

Each single tile is identified by a spec, which is a unique String for that type of tile. The
current tiles are stored as a Setting string of comma separated values of these specs. Additionally,
the default tiles (that appear on a fresh system) configuration value is stored likewise.

SystemUI tile specs are usually a single simple word identifying the tile (like `wifi`
or `battery`). Custom tile specs are always a string of the form `custom(...)` where the ellipsis is
a flattened String representing the `ComponentName` for the corresponding `TileService`.

We represent these internally using a `TileSpec` class that can distinguish between platform tiles
and custom tiles.

### CurrentTilesInteractor

This class consumes the lists of specs provided by `TileSpecRepository` and produces a
`Flow<List<Pair<TileSpec, QSTile>>>` with the current tiles for the current user.

Internally, whenever the list of tiles changes, the following operation is performed:
* Properly dispose of tiles that are no longer in the current list.
* Properly dispose of tiles that are no longer available.
* If the user has changed, relay the new user to the platform tiles and destroy any custom tiles.
* Create new tiles as needed, disposing those that are not available or when the corresponding
  service does not exist.
* Reorder the tiles.

Also, when this is completed, we pass the final list back to the repository so it matches the
correct list of tiles.

### QSFactory

`CurrentTilesInteractorImpl` uses the `QSFactory` interface to create the tiles.

This interface provides a way of creating tiles and views from a spec. It can be used in plugins to
provide different definitions for tiles.

In SystemUI there are two implementation of this factory. The first one is `QSFactoryImpl` in used for legacy tiles. The second one is `NewQSFactory` used for [NEW-ARCH] tiles.

#### QSFactoryImpl (legacy tiles)

This class implements the following method as specified in the `QSFactory` interface:

* ```java
  public QSTile createTile(String)
  ```

  Creates a tile (backend) from a given spec. The factory has a map with providers for all of the
  SystemUI tiles, returning one when the correct spec is used.

  If the spec is not recognized but it has the `custom(` prefix, the factory tries to create
  a `CustomTile` for the component in the spec.

  As part of filtering not valid tiles, custom tiles that don't have a corresponding valid service
  component are never instantiated.

#### NewQSFactory ([NEW-ARCH] tiles)

This class also implements the `createTile` method as specified in the `QSFactory` interface.
However, it first uses the spec to get a `QSTileViewModel`. The view model is then adapted into a
`QSTile` using the `QSTileViewModelAdapter`.

### Lifecycle of a Tile

We describe first the parts of the lifecycle that are common to SystemUI tiles and third party
tiles. Following that, there will be a section with the steps that are exclusive to third party
tiles.

1. The tile is added through the QS customizer by the user. This will send the new list of tiles to
   `TileSpecRepository` which will update its internal state and also store the new value in the
   secure setting `sysui_qs_tiles`. This step could also happen if `StatusBar` adds tiles (either
   through adb, or through its service interface as with the `DevelopmentTiles`).
2. This updates the flow that `CurrentTilesInteractor` is collecting from, triggering the process
   described above.
3. `CurrentTilesInteractor` calls the available `QSFactory` classes in order to find one that will
   be able to create a tile with that spec. Assuming that some factory managed to create the
   tile, which is some implementation of `QSTile` (either a SystemUI subclass
   of `QSTileImpl` or a `CustomTile`) it will be added to the current list.
   If the tile is available, it's stored in a map and things proceed forward.
4. `CurrentTilesInteractor` updates its flow and classes collecting from it will be notified of the
   change. In particular, `QSPanel` and `QuickQSPanel` receive this call with the full list of
   tiles. We will focus on these two classes.
5. For each tile in this list, a `QSTileView` is created (collapsed or expanded) and attached to
   a `TileRecord` containing the tile backend and the view. Additionally:
    * a callback is attached to the tile to communicate between the backend and the view or the
      panel.
    * the click listeners in the tile are attached to those of the view.
6. The tile view is added to the corresponding layout.

When the tile is removed from the list of current tiles, all these classes are properly disposed
including removing the callbacks and making sure that the backends remove themselves from the
controllers they were listening to.

#### Lifecycle of a CustomTile

In step 3 of the previous process, when a `CustomTile` is created, additional steps are taken to
ensure the proper binding to the service as described
in [Third party tiles (TileService)](#third-party-tiles-tileservice).

1. The `CustomTile` obtains the `TileServices` class from the `QSTileHost` and request the creation
   of a `TileServiceManager` with its token. As the spec for the `CustomTile` contains
   the `ComponentName` of the associated service, this can be used to bind to it.
2. The `TileServiceManager` creates its own `TileLifecycleManager` to take care of binding to the
   service.
3. `TileServices` creates maps between the token, the `CustomTile`, the `TileServiceManager`, the
   token and the `ComponentName`.

## Implementing a tile

This section describes necessary and recommended steps when implementing a Quick Settings tile. Some
of them are optional and depend on the requirements of the tile.

### Implementing a legacy SystemUI tile

1. Create a class (preferably
   in [`SystemUI/src/com/android/systemui/qs/tiles`](/packages/SystemUI/src/com/android/systemui/qs/tiles))
   implementing `QSTileImpl` with a particular type of `State` as a parameter.
2. Create an injectable constructor taking a `QSHost` and whichever classes are needed for the
   tile's operation. Normally this would be other SystemUI controllers.
3. Implement the methods described
   in [Abstract methods in QSTileImpl](#abstract-methods-in-qstileimpl). Look at other tiles for
   help. Some considerations to have in mind:
    * If the tile will not support long click (like the `FlashlightTile`),
      set `state.handlesLongClick` to `false` (maybe in `newTileState`).
    * Changes to the tile state (either from controllers or from clicks) should call `refreshState`.
    * Use only `handleUpdateState` to modify the values of the state to the new ones. This can be
      done by polling controllers or through the `arg` parameter.
    * If the controller is not a `CallbackController`, respond to `handleSetListening` by
      attaching/dettaching from controllers.
    * Implement `isAvailable` so the tile will not be created when it's not necessary.
4. Either create a new feature module or find an existing related feature module and add the
   following binding method:
    * ```kotlin
      @Binds
      @IntoMap
      @StringKey(YourNewTile.TILE_SPEC) // A unique word that will map to YourNewTile
      fun bindYourNewTile(yourNewTile: YourNewTile): QSTileImpl<*>
      ```
5. In [SystemUI/res/values/config.xml](/packages/SystemUI/res/values/config.xml),
   modify `quick_settings_tiles_stock` and add the spec defined in the previous step. If necessary,
   add it also to `quick_settings_tiles_default`. The first one contains a list of all the tiles
   that SystemUI knows how to create (to show to the user in the customization screen). The second
   one contains only the default tiles that the user will experience on a fresh boot or after they
   reset their tiles.
6. In [SystemUI/res/values/tiles_states_strings.xml](/packages/SystemUI/res/values/tiles_states_strings.xml),
add a new array for your tile. The name has to be `tile_states_<spec>`. Use a good description to
help the translators.
7. In [`SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt`](/packages/SystemUI/src/com/android/systemui/qs/tileimpl/QSTileViewImpl.kt),
add a new element to the map in `SubtitleArrayMapping` corresponding to the resource created in the
previous step.

#### Abstract methods in QSTileImpl

Following are methods that need to be implemented when creating a new SystemUI tile. `TState` is a
type variable of type `State`.

* ```java
    public TState newTileState()
  ```

  Creates a new `State` for this tile to use. Each time the state changes, it is copied into a new
  one and the corresponding fields are modified. The framework provides `State`, `BooleanState` (has
  an on and off state and provides this as a content description), `SignalState` (`BooleanState`
  with `activityIn` and `activityOut`), and `SlashState` (can be rotated or slashed through).

  If a tile has special behavior (no long click, no ripple), it can be set in its state here.

* ```java
    public void handleSetListening(boolean)
    ```

  Initiates or terminates listening behavior, like listening to Callbacks from controllers. This
  gets triggered when QS is expanded or collapsed (i.e., when the tile is visible and actionable).
  Most tiles (like `WifiTile`) do not implement this. Instead, Tiles are LifecycleOwner and are
  marked as `RESUMED` or `DESTROYED` in `QSTileImpl#handleListening` and handled as part of the
  lifecycle
  of [CallbackController](/packages/SystemUI/src/com/android/systemui/statusbar/policy/CallbackController.java)

* ```java
    public QSIconView createTileView(Context)
  ```

  Allows a Tile to use a `QSIconView` different from `QSIconViewImpl` (
  see [Tile views](#tile-views)), which is the default defined in `QSTileImpl`

* ```java
    public Intent getLongClickIntent()
  ```

  Determines the `Intent` launched when the Tile is long pressed.

* ```java
    protected void handleClick()

    protected void handleSecondaryClick()

    protected void handleLongClick()
  ```

  Handles what to do when the Tile is clicked. In general, a Tile will make calls to its controller
  here and maybe update its state immediately (by calling `QSTileImpl#refreshState`). A Tile can
  also decide to ignore the click here, if it's `Tile#STATE_UNAVAILABLE`.

  By default long click redirects to click and long click launches the intent defined
  in `getLongClickIntent`.

* ```java
    protected void handleUpdateState(TState, Object)
  ```

  Updates the `State` of the Tile based on the state of the device as provided by the respective
  controller. It will be called every time the Tile becomes visible, is interacted with
  or `QSTileImpl#refreshState` is called. After this is done, the updated state will be reflected in
  the UI.

* ```java
  @Deprecated
  public int getMetricsCategory()
  ```

  ~~Identifier for this Tile, as defined
  in [proto/src/metrics_constants/metrics_constants.proto](/proto/src/metrics_constants/metrics_constants.proto).
  This is used to log events related to this Tile.~~
  This is now deprecated in favor of `UiEvent` that use the tile spec.

* ```java
  public boolean isAvailable()
  ```

  Determines if a Tile is available to be used (for example, disable `WifiTile` in devices with no
  Wifi support). If this is false, the Tile will be destroyed upon creation.

* ```java
  public CharSequence getTileLabel()
  ```

  Provides a default label for this Tile. Used by the QS Panel customizer to show a name next to
  each available tile.

### Implementing a [NEW-ARCH] SystemUI tile
In the new system the tiles are created in the path
[`packages/SystemUI/src/com/android/systemui/qs/tiles/impl/<spec>`](packages/SystemUI/src/com/android/systemui/qs/tiles/impl/<spec>)
where the `<spec>` should be replaced by the spec of the tile e.g. rotation for `RotationLockTile`.

To create a new tile, the developer needs to implement the following data class and interfaces:

[`DataModel`] is a class that describes the system state of the feature that the tile is trying to
represent. Let's refer to the type of this class as DATA_TYPE. For example a simplified version of
the data model for a flashlight tile could be a class with a boolean field that represents
whether the flashlight is on or not.

This file should be placed in the relative path `domain/model/` down from the tile's package.

[`QSTileDataInteractor`] There are two abstract methods that need to be implemented:
* `fun tileData(user: UserHandle, triggers: Flow<DataUpdateTrigger>): Flow<DATA_TYPE>`: This method
returns a flow of data that will be used to create the state of the tile. This is where the system
state is listened to and converted to a flow of data model. Avoid loading data or settings up
listeners on the main thread. The userHandle is the user for which the tile is created.
The triggers flow is a flow of events that can be used to trigger a refresh of the data.
The most common triggers the force update and initial request.

* `fun availability(user: UserHandle): Flow<Boolean>`: This method returns a flow of booleans that
indicates if the tile should be shown or not. This is where the availability of the system feature
(e.g. wifi) is checked. The userHandle is the user for which the tile is created.

This file should be placed in the relative path `domain/interactor/` down from the tile's package.

[`QSTileUserActionInteractor`]
* `fun handleInput(input: QSTileInput)` is the method that needs to be implemented. This is the
method that will be called when the user interacts with the tile. The input parameter contains
the type of interaction (click, long click, toggle) and the DATA_TYPE of the latest data when the
input was received.

This file should be placed in the relative path `/domain/interactor` down from the tile's package.

[`QSTileDataToStateMapper`]
* `fun map(data: DATA_TYPE): QSTileState` is the method that needs to be implemented. This method
is responsible for mapping the data received from the data interactor to a state that the view
model can use to update the UI. This is where for example the icon should be loaded, and the
label and content description set. The map function will run on UIBackground thread, a single
thread which has higher priority than the background thread and lower than UI thread. Loading a
resource on UI thread can cause jank by blocking the UI thread. On the other end of the spectrum,
loading resources using a background dispatcher may cause jank due to background thread contention
since it is possible for the background dispatcher to use more than one background thread
at the same time. In contrast, the UIBackground dispatcher uses a single thread that is
shared by all tiles. Therefore the system will use UIBackground dispatcher to execute
the map function.

The most important resource to load in the map function is the icon. We prefer `Icon.Loaded` with a
resource id over `Icon.Resource`, because then (a) we can guarantee that the drawable loading will
happen on the UIBackground thread and (b) we can cache the drawables using the resource id.

This file should be placed in the relative path `/ui/mapper` down from the tile's package.

#### Testing a [NEW-ARCH] SystemUI tile

When writing tests, the mapper is usually a good place to start, since that is where the
business logic decisions are being made that can inform the shape of data interactor.

We suggest taking advantage of the existing class `QSTileStateSubject`. So rather than
asserting an individual field's value, a test will assert the whole state. That can be
achieved by `QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)`.

### Implementing a third party tile

For information about this, use the Android Developer documentation
for [TileService](https://developer.android.com/reference/android/service/quicksettings/TileService).

## AutoAddable tiles

AutoAddable tiles are tiles that are not part of the default set, but will be automatically added
for the user, when the user enabled a feature for the first time. For example:
* When the user creates a work profile, the work profile tile is automatically added.
* When the user sets up a hotspot for the first time, the hotspot tile is automatically added.

In order to declare a tile as auto-addable, there are two ways:

* If the tile can be tied to a secure setting such that the tile should be auto added after that
  setting has changed to a non-zero value for the first time, a new line can be added to the
  string-array `config_quickSettingsAutoAdd` in [config.xml](/packages/SystemUI/res/values/config.xml).
* If more specific behavior is needed, a new
  [AutoAddable](/packages/SystemUI/src/com/android/systemui/qs/pipeline/domain/model/AutoAddable.kt)
  can be added in the `autoaddables` package. This can have custom logic that produces a flow of
  signals on when the tile should be auto-added (or auto-removed in special cases).

  *Special case: If the data comes from a `CallbackController`, a special
  `CallbackControllerAutoAddable` can be created instead that handles a lot of the common code.*

### AutoAddRepository (and UserAutoAddRepository)

These classes keep track of tiles that have been auto-added for each user, as a list of Tile specs.
While the device is running, this is the source of truth of already auto-added tiles for that user.

The list is persisted to `Settings.Secure` every time it changes so it will be available upon
restart or backup. In particular, any changes in the secure setting while this repository is
tracking the list of tiles will be reverted.

The class provides a `Flow<Set<TileSpec>>` for each user that can be collected to keep track of the
set of already auto added tiles.

### AutoAddInteractor

This class collects all registered (through Dagger) `AutoAddables` and merges all the signals for
the current user. It will add/remove tiles as necessary and mark them as such in the
`AutoAddRepository`.

## Backup and restore

It's important to point out that B&R of Quick Settings tiles only concerns itself with restoring,
for each user, the list of current tiles and their order. The state of the tiles (or other things
that can be accessed from them like list of WiFi networks) is the concern of each feature team and
out of the scope of Quick Settings.

In order to provide better support to restoring Quick Settings tiles and prevent overwritten or
inconsistent data, the system has the following steps:

1. When `Settings.Secure.SYSUI_QS_TILES` and `Settings.Secure.QS_AUTO_TILES` are restored, a
  broadcast is sent to SystemUI. This is handled by
  [SettingsHelper](/packages/SettingsProvider/src/com/android/providers/settings/SettingsHelper.java).
  The broadcasts are received by [QSSettingsRestoredRepository](/packages/SystemUI/src/com/android/systemui/qs/pipeline/data/repository/QSSettingsRestoredRepository.kt)
  and grouped by user into a data object. As described above, the change performed by the restore in
  settings is overriden by the corresponding repositories.
2. Once both settings have been restored, the data is reconciled with the current data, to account
  for tiles that may have been auto-added between the start of SystemUI and the time the restore
  happened. The guiding principles for the reconciliation are as follows:
    * We assume that the user expects the restored tiles to be the ones to be present after restore,
      so those are taken as the basis for the reconciliation.
    * Any tile that was auto-added before the restore, but had not been auto-added in the source
      device, is auto-added again (preferably in a similar position).
    * Any tile that was auto-added before the restore, and it was also auto-added in the source
      device, but not present in the restored tiles, is considered removed by the user and therefore
      not restored.
    * Every tile that was marked as auto-added (all tiles in source + tiles added before restore)
      are set as auto-added.

## Logs for debugging

The following log buffers are used for Quick Settings debugging purposes:

### QSLog

Logs events in the individual tiles, like listening state, clicks, and status updates.

### QSTileListLog

Logs changes in the current set of tiles for each user, including when tiles are created or
destroyed, and the reason for that. It also logs what operation caused the tiles to change
(add, remove, change, restore).

### QSAutoAddLog

Logs operations of auto-add (or auto-remove) of tiles.

### QSRestoreLog

Logs the data obtained after a successful restore of the settings. This is the data that will be
used for reconciliation.