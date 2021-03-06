package bdv.viewer;

import static bdv.viewer.VisibilityAndGrouping.Event.CURRENT_SOURCE_CHANGED;
import static bdv.viewer.VisibilityAndGrouping.Event.DISPLAY_MODE_CHANGED;
import static bdv.viewer.VisibilityAndGrouping.Event.GROUP_ACTIVITY_CHANGED;
import static bdv.viewer.VisibilityAndGrouping.Event.GROUP_NAME_CHANGED;
import static bdv.viewer.VisibilityAndGrouping.Event.NUM_SOURCES_CHANGED;
import static bdv.viewer.VisibilityAndGrouping.Event.SOURCE_ACTVITY_CHANGED;
import static bdv.viewer.VisibilityAndGrouping.Event.VISIBILITY_CHANGED;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import net.imglib2.Positionable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.InteractiveDisplayCanvasComponent;
import net.imglib2.ui.OverlayRenderer;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.TransformEventHandler;
import net.imglib2.ui.TransformEventHandler3D;
import net.imglib2.ui.TransformEventHandlerFactory;
import net.imglib2.ui.TransformListener;
import net.imglib2.util.LinAlgHelpers;

import org.jdom2.Element;

import bdv.img.cache.Cache;
import bdv.util.Affine3DHelpers;
import bdv.util.Prefs;
import bdv.viewer.animate.AbstractTransformAnimator;
import bdv.viewer.animate.MessageOverlayAnimator;
import bdv.viewer.animate.OverlayAnimator;
import bdv.viewer.animate.RotationAnimator;
import bdv.viewer.animate.TextOverlayAnimator;
import bdv.viewer.animate.TextOverlayAnimator.TextPosition;
import bdv.viewer.overlay.MultiBoxOverlayRenderer;
import bdv.viewer.overlay.ScaleBarOverlayRenderer;
import bdv.viewer.overlay.SourceInfoOverlayRenderer;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.TransformAwareBufferedImageOverlayRenderer;
import bdv.viewer.state.SourceGroup;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;
import bdv.viewer.state.XmlIoViewerState;


/**
 * A JPanel for viewing multiple of {@link Source}s. The panel contains a
 * {@link InteractiveDisplayCanvasComponent canvas} and a time slider (if there
 * are multiple time-points). Maintains a {@link ViewerState render state}, the
 * renderer, and basic navigation help overlays. It has it's own
 * {@link PainterThread} for painting, which is started on construction (use
 * {@link #stop() to stop the PainterThread}.
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class ViewerPanel extends JPanel implements OverlayRenderer, TransformListener< AffineTransform3D >, PainterThread.Paintable, VisibilityAndGrouping.UpdateListener
{
	private static final long serialVersionUID = 1L;

	/**
	 * Currently rendered state (visible sources, transformation, timepoint,
	 * etc.) A copy can be obtained by {@link #getState()}.
	 */
	protected final ViewerState state;

	/**
	 * Renders the current state for the {@link #display}.
	 */
	protected final MultiResolutionRenderer imageRenderer;

	/**
	 * TODO
	 */
	protected final TransformAwareBufferedImageOverlayRenderer renderTarget;

	/**
	 * Overlay navigation boxes.
	 */
	// TODO: move to specialized class
	protected final MultiBoxOverlayRenderer multiBoxOverlayRenderer;

	/**
	 * Overlay current source name and current timepoint.
	 */
	// TODO: move to specialized class
	protected final SourceInfoOverlayRenderer sourceInfoOverlayRenderer;

	/**
	 * TODO
	 */
	protected final ScaleBarOverlayRenderer scaleBarOverlayRenderer;

	/**
	 * Transformation set by the interactive viewer.
	 */
	protected final AffineTransform3D viewerTransform;

	/**
	 * Canvas used for displaying the rendered {@link #renderTarget image} and
	 * overlays.
	 */
	protected final InteractiveDisplayCanvasComponent< AffineTransform3D > display;

	protected final JSlider sliderTime;

	/**
	 * Thread that triggers repainting of the display.
	 */
	protected final PainterThread painterThread;

	/**
	 * The {@link ExecutorService} used for rendereing.
	 */
	protected final ExecutorService renderingExecutorService;

	/**
	 * Keeps track of the current mouse coordinates, which are used to provide
	 * the current global position (see {@link #getGlobalMouseCoordinates(RealPositionable)}).
	 */
	protected final MouseCoordinateListener mouseCoordinates;

	/**
	 * Manages visibility and currentness of sources and groups, as well as
	 * grouping of sources, and display mode.
	 */
	protected final VisibilityAndGrouping visibilityAndGrouping;

	/**
	 * These listeners will be notified about changes to the
	 * {@link #viewerTransform}. This is done <em>before</em> calling
	 * {@link #requestRepaint()} so listeners have the chance to interfere.
	 */
	protected final CopyOnWriteArrayList< TransformListener< AffineTransform3D > > transformListeners;

	/**
	 * These listeners will be notified about changes to the
	 * {@link #viewerTransform} that was used to render the current image. This
	 * is intended for example for {@link OverlayRenderer}s that need to exactly
	 * match the transform of their overlaid content to the transform of the
	 * image.
	 */
	protected final CopyOnWriteArrayList< TransformListener< AffineTransform3D > > lastRenderTransformListeners;

	/**
	 * Current animator for viewer transform, or null. This is for example used
	 * to make smooth transitions when {@link #align(AlignPlane) aligning to
	 * orthogonal planes}.
	 */
	protected AbstractTransformAnimator currentAnimator = null;

	/**
	 * A list of currently incomplete (see {@link OverlayAnimator#isComplete()})
	 * animators. Initially, this contains a {@link TextOverlayAnimator} showing
	 * the "press F1 for help" message.
	 */
	protected ArrayList< OverlayAnimator > overlayAnimators;

	/**
	 * Fade-out overlay of recent messages. See {@link #showMessage(String)}.
	 */
	protected final MessageOverlayAnimator msgOverlay;

	/**
	 * Optional parameters for {@link ViewerPanel}.
	 */
	public static class Options
	{
		private int width = 800;

		private int height = 600;

		private double[] screenScales = new double[] { 1, 0.75, 0.5, 0.25, 0.125 };

		private long targetRenderNanos = 30 * 1000000l;

		private boolean doubleBuffered = true;

		private int numRenderingThreads = 3;

		private boolean useVolatileIfAvailable = true;

		private MessageOverlayAnimator msgOverlay = new MessageOverlayAnimator( 800 );

		private TransformEventHandlerFactory< AffineTransform3D > transformEventHandlerFactory = TransformEventHandler3D.factory();

		public Options width( final int w )
		{
			width = w;
			return this;
		}

		public Options height( final int h )
		{
			height = h;
			return this;
		}

		public Options screenScales( final double[] s )
		{
			screenScales = s;
			return this;
		}

		public Options targetRenderNanos( final long t )
		{
			targetRenderNanos = t;
			return this;
		}

		public Options doubleBuffered( final boolean d )
		{
			doubleBuffered = d;
			return this;
		}

		public Options numRenderingThreads( final int n )
		{
			numRenderingThreads = n;
			return this;
		}

		public Options useVolatileIfAvailable( final boolean v )
		{
			useVolatileIfAvailable = v;
			return this;
		}

		public Options msgOverlay( final MessageOverlayAnimator o )
		{
			msgOverlay = o;
			return this;
		}

		public Options transformEventHandlerFactory( final TransformEventHandlerFactory< AffineTransform3D > f )
		{
			transformEventHandlerFactory = f;
			return this;
		}
	}

	/**
	 * Create default {@link Options}.
	 * @return default {@link Options}.
	 */
	public static Options options()
	{
		return new Options();
	}

	public ViewerPanel( final List< SourceAndConverter< ? > > sources, final int numTimePoints, final Cache cache )
	{
		this( sources, numTimePoints, cache, options() );
	}

	/**
	 * @param sources
	 *            the {@link SourceAndConverter sources} to display.
	 * @param numTimePoints
	 *            number of available timepoints.
	 * @param cache
	 *            to control IO budgeting and fetcher queue.
	 * @param optional
	 *            optional parameters. See {@link #options()}.
	 */
	public ViewerPanel( final List< SourceAndConverter< ? > > sources, final int numTimePoints, final Cache cache, final Options optional )
	{
		super( new BorderLayout(), false );

		final int numGroups = 10;
		final ArrayList< SourceGroup > groups = new ArrayList< SourceGroup >( numGroups );
		for ( int i = 0; i < numGroups; ++i )
			groups.add( new SourceGroup( "group " + Integer.toString( i + 1 ), null ) );
		state = new ViewerState( sources, groups, numTimePoints );
		for ( int i = Math.min( numGroups, sources.size() ) - 1; i >= 0; --i )
			state.getSourceGroups().get( i ).addSource( i );

		if ( !sources.isEmpty() )
			state.setCurrentSource( 0 );
		multiBoxOverlayRenderer = new MultiBoxOverlayRenderer();
		sourceInfoOverlayRenderer = new SourceInfoOverlayRenderer();
		scaleBarOverlayRenderer = Prefs.showScaleBar() ? new ScaleBarOverlayRenderer() : null;

		painterThread = new PainterThread( this );
		viewerTransform = new AffineTransform3D();
		display = new InteractiveDisplayCanvasComponent< AffineTransform3D >(
				optional.width, optional.height, optional.transformEventHandlerFactory );
		display.addTransformListener( this );
		renderTarget = new TransformAwareBufferedImageOverlayRenderer();
		renderTarget.setCanvasSize( optional.width, optional.height );
		display.addOverlayRenderer( renderTarget );
		display.addOverlayRenderer( this );

		renderingExecutorService = Executors.newFixedThreadPool( optional.numRenderingThreads );
		imageRenderer = new MultiResolutionRenderer(
				renderTarget, painterThread,
				optional.screenScales, optional.targetRenderNanos, optional.doubleBuffered,
				optional.numRenderingThreads, renderingExecutorService, optional.useVolatileIfAvailable, cache );

		mouseCoordinates = new MouseCoordinateListener();
		display.addHandler( mouseCoordinates );

		add( display, BorderLayout.CENTER );
		if ( numTimePoints > 1 )
		{
			sliderTime = new JSlider( SwingConstants.HORIZONTAL, 0, numTimePoints - 1, 0 );
			sliderTime.addChangeListener( new ChangeListener()
			{
				@Override
				public void stateChanged( final ChangeEvent e )
				{
					if ( e.getSource().equals( sliderTime ) )
						setTimepoint( sliderTime.getValue() );
				}
			} );
			add( sliderTime, BorderLayout.SOUTH );
		}
		else
			sliderTime = null;

		visibilityAndGrouping = new VisibilityAndGrouping( state );
		visibilityAndGrouping.addUpdateListener( this );

		transformListeners = new CopyOnWriteArrayList< TransformListener< AffineTransform3D > >();
		lastRenderTransformListeners = new CopyOnWriteArrayList< TransformListener< AffineTransform3D > >();

		msgOverlay = optional.msgOverlay;

		overlayAnimators = new ArrayList< OverlayAnimator >();
		overlayAnimators.add( msgOverlay );
		overlayAnimators.add( new TextOverlayAnimator( "Press <F1> for help.", 3000, TextPosition.CENTER ) );

		display.addComponentListener( new ComponentAdapter()
		{
			@Override
			public void componentResized( final ComponentEvent e )
			{
				requestRepaint();
				display.removeComponentListener( this );
			}
		} );

		painterThread.start();
	}

	public void addSource( final SourceAndConverter< ? > sourceAndConverter )
	{
		synchronized ( visibilityAndGrouping )
		{
			state.addSource( sourceAndConverter );
			visibilityAndGrouping.update( NUM_SOURCES_CHANGED );
		}
		requestRepaint();
	}

	public void removeSource( final Source< ? > source )
	{
		synchronized ( visibilityAndGrouping )
		{
			state.removeSource( source );
			visibilityAndGrouping.update( NUM_SOURCES_CHANGED );
		}
		requestRepaint();
	}

	// TODO: remove?
//	public void addHandler( final Object handler )
//	{
//		display.addHandler( handler );
//	}

	/**
	 * Set {@code gPos} to the current mouse coordinates transformed into the
	 * global coordinate system.
	 *
	 * @param gPos
	 *            is set to the current global coordinates.
	 */
	public void getGlobalMouseCoordinates( final RealPositionable gPos )
	{
		assert gPos.numDimensions() == 3;
		final RealPoint lPos = new RealPoint( 3 );
		mouseCoordinates.getMouseCoordinates( lPos );
		viewerTransform.applyInverse( gPos, lPos );
	}

	/**
	 * TODO
	 * @param p
	 */
	public synchronized void getMouseCoordinates( final Positionable p )
	{
		assert p.numDimensions() == 2;
		mouseCoordinates.getMouseCoordinates( p );
	}

	@Override
	public void paint()
	{
		imageRenderer.paint( state );

		display.repaint();

		synchronized ( this )
		{
			if ( currentAnimator != null )
			{
				final TransformEventHandler< AffineTransform3D > handler = display.getTransformEventHandler();
				final AffineTransform3D transform = currentAnimator.getCurrent( System.currentTimeMillis() );
				handler.setTransform( transform );
				transformChanged( transform );
				if ( currentAnimator.isComplete() )
					currentAnimator = null;
			}
		}
	}

	/**
	 * Repaint as soon as possible.
	 */
	public void requestRepaint()
	{
		imageRenderer.requestRepaint();
	}

	@Override
	public void drawOverlays( final Graphics g )
	{
		multiBoxOverlayRenderer.setViewerState( state );
		multiBoxOverlayRenderer.updateVirtualScreenSize( display.getWidth(), display.getHeight() );
		multiBoxOverlayRenderer.paint( ( Graphics2D ) g );

		sourceInfoOverlayRenderer.setViewerState( state );
		sourceInfoOverlayRenderer.paint( ( Graphics2D ) g );

		if ( Prefs.showScaleBar() )
		{
			scaleBarOverlayRenderer.setViewerState( state );
			scaleBarOverlayRenderer.paint( ( Graphics2D ) g );
		}

		final RealPoint gPos = new RealPoint( 3 );
		getGlobalMouseCoordinates( gPos );
		final String mousePosGlobalString = String.format( "(%6.1f,%6.1f,%6.1f)", gPos.getDoublePosition( 0 ), gPos.getDoublePosition( 1 ), gPos.getDoublePosition( 2 ) );

		g.setFont( new Font( "Monospaced", Font.PLAIN, 12 ) );
		g.setColor( Color.white );
		g.drawString( mousePosGlobalString, ( int ) g.getClipBounds().getWidth() - 170, 25 );

		boolean requiresRepaint = multiBoxOverlayRenderer.isHighlightInProgress();

		final long currentTimeMillis = System.currentTimeMillis();
		final ArrayList< OverlayAnimator > overlayAnimatorsToRemove = new ArrayList< OverlayAnimator >();
		for ( final OverlayAnimator animator : overlayAnimators )
		{
			animator.paint( ( Graphics2D ) g, currentTimeMillis );
			requiresRepaint |= animator.requiresRepaint();
			if ( animator.isComplete() )
				overlayAnimatorsToRemove.add( animator );
		}
		overlayAnimators.removeAll( overlayAnimatorsToRemove );

		if ( requiresRepaint )
			display.repaint();
	}

	@Override
	public synchronized void transformChanged( final AffineTransform3D transform )
	{
		viewerTransform.set( transform );
		state.setViewerTransform( transform );
		for ( final TransformListener< AffineTransform3D > l : transformListeners )
			l.transformChanged( viewerTransform );
		requestRepaint();
	}

	@Override
	public void visibilityChanged( final VisibilityAndGrouping.Event e )
	{
		switch ( e.id )
		{
		case CURRENT_SOURCE_CHANGED:
			multiBoxOverlayRenderer.highlight( visibilityAndGrouping.getCurrentSource() );
			display.repaint();
			break;
		case DISPLAY_MODE_CHANGED:
			showMessage( visibilityAndGrouping.getDisplayMode().getName() );
			display.repaint();
			break;
		case GROUP_NAME_CHANGED:
			display.repaint();
			break;
		case SOURCE_ACTVITY_CHANGED:
			// TODO multiBoxOverlayRenderer.highlight() all sources that became visible
			break;
		case GROUP_ACTIVITY_CHANGED:
			// TODO multiBoxOverlayRenderer.highlight() all sources that became visible
			break;
		case VISIBILITY_CHANGED:
			requestRepaint();
			break;
		}
	}

	private final static double c = Math.cos( Math.PI / 4 );

	/**
	 * The planes which can be aligned with the viewer coordinate system: XY,
	 * ZY, and XZ plane.
	 */
	public static enum AlignPlane
	{
		XY( "XY", 2, new double[] { 1, 0, 0, 0 } ),
		ZY( "ZY", 0, new double[] { c, 0, -c, 0 } ),
		XZ( "XZ", 1, new double[] { c, c, 0, 0 } );

		private final String name;

		public String getName()
		{
			return name;
		}

		/**
		 * rotation from the xy-plane aligned coordinate system to this plane.
		 */
		private final double[] qAlign;

		/**
		 * Axis index. The plane spanned by the remaining two axes will be
		 * transformed to the same plane by the computed rotation and the
		 * "rotation part" of the affine source transform.
		 * @see Affine3DHelpers#extractApproximateRotationAffine(AffineTransform3D, double[], int)
		 */
		private final int coerceAffineDimension;

		private AlignPlane( final String name, final int coerceAffineDimension, final double[] qAlign )
		{
			this.name = name;
			this.coerceAffineDimension = coerceAffineDimension;
			this.qAlign = qAlign;
		}
	}

	/**
	 * Align the XY, ZY, or XZ plane of the local coordinate system of the
	 * currently active source with the viewer coordinate system.
	 *
	 * @param plane
	 *            to which plane to align.
	 */
	protected synchronized void align( final AlignPlane plane )
	{
		final SourceState< ? > source = state.getSources().get( state.getCurrentSource() );
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		source.getSpimSource().getSourceTransform( state.getCurrentTimepoint(), 0, sourceTransform );

		final double[] qSource = new double[ 4 ];
		Affine3DHelpers.extractRotationAnisotropic( sourceTransform, qSource );

		final double[] qTmpSource = new double[ 4 ];
		Affine3DHelpers.extractApproximateRotationAffine( sourceTransform, qSource, plane.coerceAffineDimension );
		LinAlgHelpers.quaternionMultiply( qSource, plane.qAlign, qTmpSource );

		final double[] qTarget = new double[ 4 ];
		LinAlgHelpers.quaternionInvert( qTmpSource, qTarget );

		final AffineTransform3D transform = display.getTransformEventHandler().getTransform();
		double centerX;
		double centerY;
		if ( mouseCoordinates.isMouseInsidePanel() )
		{
			centerX = mouseCoordinates.getX();
			centerY = mouseCoordinates.getY();
		}
		else
		{
			centerY = getHeight() / 2.0;
			centerX = getWidth() / 2.0;
		}
		currentAnimator = new RotationAnimator( transform, centerX, centerY, qTarget, 300 );
		currentAnimator.setTime( System.currentTimeMillis() );
		transformChanged( transform );
	}

	public synchronized void setTransformAnimator( final AbstractTransformAnimator animator )
	{
		currentAnimator = animator;
		currentAnimator.setTime( System.currentTimeMillis() );
		requestRepaint();
	}

	/**
	 * Switch to next interpolation mode. (Currently, there are two
	 * interpolation modes: nearest-neighbor and N-linear.
	 */
	public synchronized void toggleInterpolation()
	{
		final Interpolation interpolation = state.getInterpolation();
		if ( interpolation == Interpolation.NEARESTNEIGHBOR )
		{
			state.setInterpolation( Interpolation.NLINEAR );
			showMessage( "tri-linear interpolation" );
		}
		else
		{
			state.setInterpolation( Interpolation.NEARESTNEIGHBOR );
			showMessage( "nearest-neighbor interpolation" );
		}
		requestRepaint();
	}

	/**
	 * Set the {@link DisplayMode}.
	 */
	public synchronized void setDisplayMode( final DisplayMode displayMode )
	{
		visibilityAndGrouping.setDisplayMode( displayMode );
	}

	/**
	 * Set the viewer transform.
	 */
	public synchronized void setCurrentViewerTransform( final AffineTransform3D viewerTransform )
	{
		display.getTransformEventHandler().setTransform( viewerTransform );
		transformChanged( viewerTransform );
	}

	/**
	 * Show the specified time-point.
	 *
	 * @param timepoint
	 *            time-point index.
	 */
	public synchronized void setTimepoint( final int timepoint )
	{
		if ( state.getCurrentTimepoint() != timepoint )
		{
			state.setCurrentTimepoint( timepoint );
			sliderTime.setValue( timepoint );
			requestRepaint();
		}
	}

	/**
	 * Show the next time-point.
	 */
	public synchronized void nextTimePoint()
	{
		if ( sliderTime != null )
			sliderTime.setValue( sliderTime.getValue() + 1 );
	}

	/**
	 * Show the previous time-point.
	 */
	public synchronized void previousTimePoint()
	{
		if ( sliderTime != null )
			sliderTime.setValue( sliderTime.getValue() - 1 );
	}

	/**
	 * Get a copy of the current {@link ViewerState}.
	 *
	 * @return a copy of the current {@link ViewerState}.
	 */
	public synchronized ViewerState getState()
	{
		return state.copy();
	}

	/**
	 * Get the viewer canvas.
	 *
	 * @return the viewer canvas.
	 */
	public InteractiveDisplayCanvasComponent< AffineTransform3D > getDisplay()
	{
		return display;
	}

	/**
	 * Display the specified message in a text overlay for a short time.
	 *
	 * @param msg
	 *            String to display. Should be just one line of text.
	 */
	public void showMessage( final String msg )
	{
		msgOverlay.add( msg );
		display.repaint();
	}

	/**
	 * Add a new {@link OverlayAnimator} to the list of animators. The animation
	 * is immediately started. The new {@link OverlayAnimator} will remain in
	 * the list of animators until it {@link OverlayAnimator#isComplete()}.å
	 *
	 * @param animator
	 *            animator to add.
	 */
	public void addOverlayAnimator( final OverlayAnimator animator )
	{
		overlayAnimators.add( animator );
		display.repaint();
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation
	 * changes. Listeners will be notified when a new image has been painted
	 * with the viewer transform used to render that image.
	 *
	 * This happens immediately after that image is painted onto the screen,
	 * before any overlays are painted.
	 *
	 * @param listener
	 *            the transform listener to add.
	 */
	public synchronized void addRenderTransformListener( final TransformListener< AffineTransform3D > listener )
	{
		renderTarget.addTransformListener( listener );
	}

	/**
	/**
	 * Add a {@link TransformListener} to notify about viewer transformation
	 * changes. Listeners will be notified when a new image has been painted
	 * with the viewer transform used to render that image.
	 *
	 * This happens immediately after that image is painted onto the screen,
	 * before any overlays are painted.
	 *
	 * @param listener
	 *            the transform listener to add.
	 * @param index
	 *            position in the list of listeners at which to insert this one.
	 */
	public void addRenderTransformListener( final TransformListener< AffineTransform3D > listener, final int index )
	{
		renderTarget.addTransformListener( listener, index );
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation
	 * changes. Listeners will be notified <em>before</em> calling
	 * {@link #requestRepaint()} so they have the chance to interfere.
	 *
	 * @param listener
	 *            the transform listener to add.
	 */
	public synchronized void addTransformListener( final TransformListener< AffineTransform3D > listener )
	{
		addTransformListener( listener, Integer.MAX_VALUE );
	}

	/**
	 * Add a {@link TransformListener} to notify about viewer transformation
	 * changes. Listeners will be notified <em>before</em> calling
	 * {@link #requestRepaint()} so they have the chance to interfere.
	 *
	 * @param listener
	 *            the transform listener to add.
	 * @param index
	 *            position in the list of listeners at which to insert this one.
	 */
	public void addTransformListener( final TransformListener< AffineTransform3D > listener, final int index )
	{
		synchronized ( transformListeners )
		{
			final int s = transformListeners.size();
			transformListeners.add( index < 0 ? 0 : index > s ? s : index, listener );
			listener.transformChanged( viewerTransform );
		}
	}

	/**
	 * Remove a {@link TransformListener}.
	 *
	 * @param listener
	 *            the transform listener to remove.
	 */
	public synchronized void removeTransformListener( final TransformListener< AffineTransform3D > listener )
	{
		synchronized ( transformListeners )
		{
			transformListeners.remove( listener );
		}
		renderTarget.removeTransformListener( listener );
	}

	protected class MouseCoordinateListener implements MouseMotionListener, MouseListener
	{
		private int x;

		private int y;

		private boolean isInside;

		public synchronized void getMouseCoordinates( final Positionable p )
		{
			p.setPosition( x, 0 );
			p.setPosition( y, 1 );
		}

		@Override
		public synchronized void mouseDragged( final MouseEvent e )
		{
			x = e.getX();
			y = e.getY();
		}

		@Override
		public synchronized void mouseMoved( final MouseEvent e )
		{
			x = e.getX();
			y = e.getY();
			display.repaint(); // TODO: only when overlays are visible
		}

		public synchronized int getX()
		{
			return x;
		}

		public synchronized int getY()
		{
			return y;
		}

		public synchronized boolean isMouseInsidePanel()
		{
			return isInside;
		}

		@Override
		public synchronized void mouseEntered( final MouseEvent e )
		{
			isInside = true;
		}

		@Override
		public synchronized void mouseExited( final MouseEvent e )
		{
			isInside = false;
		}

		@Override
		public void mouseClicked( final MouseEvent e )
		{}

		@Override
		public void mousePressed( final MouseEvent e )
		{}

		@Override
		public void mouseReleased( final MouseEvent e )
		{}
	}

	public synchronized Element stateToXml()
	{
		return new XmlIoViewerState().toXml( state );
	}

	public synchronized void stateFromXml( final Element parent )
	{
		final XmlIoViewerState io = new XmlIoViewerState();
		io.restoreFromXml( parent.getChild( io.getTagName() ), state );
	}

	/**
	 * does nothing.
	 */
	@Override
	public void setCanvasSize( final int width, final int height )
	{}

	/**
	 * Returns the {@link VisibilityAndGrouping} that can be used to modify
	 * visibility and currentness of sources and groups, as well as grouping of
	 * sources, and display mode.
	 */
	public VisibilityAndGrouping getVisibilityAndGrouping()
	{
		return visibilityAndGrouping;
	}

	/**
	 * Stop the {@link #painterThread} and unsubscribe as a cache consumer.
	 */
	public void stop()
	{
		painterThread.interrupt();
		renderingExecutorService.shutdown();
	}
}
