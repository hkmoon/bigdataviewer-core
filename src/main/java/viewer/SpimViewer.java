package viewer;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collection;

import javax.swing.JFrame;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import net.imglib.ui.OverlayRenderer;
import net.imglib.ui.PainterThread;
import net.imglib.ui.component.InteractiveDisplay3DCanvas;
import net.imglib2.Positionable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.ui.TransformEventHandler3D;
import net.imglib2.ui.TransformListener3D;
import net.imglib2.util.LinAlgHelpers;
import viewer.TextOverlayAnimator.TextPosition;
import viewer.refactor.Interpolation;
import viewer.refactor.MultiResolutionRenderer;
import viewer.refactor.SpimSourceState;
import viewer.refactor.SpimViewerState;
import viewer.refactor.overlay.MultiBoxOverlayRenderer;
import viewer.refactor.overlay.SourceInfoOverlayRenderer;

public class SpimViewer implements OverlayRenderer, TransformListener3D, PainterThread.Paintable
{
	protected SpimViewerState state;

	protected MultiResolutionRenderer imageRenderer;

	protected MultiBoxOverlayRenderer multiBoxOverlayRenderer;

	protected SourceInfoOverlayRenderer sourceInfoOverlayRenderer;

	/**
	 * Transformation set by the interactive viewer.
	 */
	final protected AffineTransform3D viewerTransform;

	/**
	 * Canvas used for displaying the rendered {@link #screenImages screen image}.
	 */
	final protected InteractiveDisplay3DCanvas display;

	/**
	 * Thread that triggers repainting of the display.
	 */
	final protected PainterThread painterThread;

	final protected JFrame frame;

	final protected JSlider sliderTime;

	final protected MouseCoordinateListener mouseCoordinates;


	/**
	 *
	 * @param width
	 *            width of the display window.
	 * @param height
	 *            height of the display window.
	 * @param sources
	 *            the {@link SpimSourceAndConverter sources} to display.
	 * @param numTimePoints
	 *            number of available timepoints.
	 * @param numMipmapLevels
	 *            number of available mipmap levels.
	 */
	public SpimViewer( final int width, final int height, final Collection< SpimSourceAndConverter< ? > > sources, final int numTimePoints, final int numMipmapLevels )
	{
		state = new SpimViewerState( sources, numTimePoints, numMipmapLevels );
		if ( ! sources.isEmpty() )
			state.setCurrentSource( 0 );
		multiBoxOverlayRenderer = new MultiBoxOverlayRenderer( width, height );
		sourceInfoOverlayRenderer = new SourceInfoOverlayRenderer();

		painterThread = new PainterThread( this );
		viewerTransform = new AffineTransform3D();
		display = new InteractiveDisplay3DCanvas( width, height, this, this );

		final double[] screenScales = new double[] { 1, 0.75, 0.5, 0.25, 0.125 };
		final long targetRenderNanos = 30 * 1000000;
		final long targetIoNanos = 10 * 1000000;
		final int badIoFrameBlockFrames = 5;
		imageRenderer = new MultiResolutionRenderer( display, painterThread, screenScales, numMipmapLevels, targetRenderNanos, targetIoNanos, badIoFrameBlockFrames );

		mouseCoordinates = new MouseCoordinateListener() ;
		display.addHandler( mouseCoordinates );

//		final SourceSwitcher sourceSwitcher = new SourceSwitcher();
//		display.addKeyListener( sourceSwitcher );

		sliderTime = new JSlider( JSlider.HORIZONTAL, 0, numTimePoints - 1, 0 );
//		sliderTime.addKeyListener( display.getTransformEventHandler() );
//		sliderTime.addKeyListener( sourceSwitcher );
		sliderTime.addChangeListener( new ChangeListener() {
			@Override
			public void stateChanged( final ChangeEvent e )
			{
				if ( e.getSource().equals( sliderTime ) )
					updateTimepoint( sliderTime.getValue() );
			}
		} );

//		final GraphicsConfiguration gc = GuiHelpers.getSuitableGraphicsConfiguration( ARGBScreenImage.ARGB_COLOR_MODEL );
		final GraphicsConfiguration gc = GuiHelpers.getSuitableGraphicsConfiguration( GuiHelpers.RGB_COLOR_MODEL );
		frame = new JFrame( "multi-angle viewer", gc );
		frame.getRootPane().setDoubleBuffered( true );
		final Container content = frame.getContentPane();
		content.add( display, BorderLayout.CENTER );
		content.add( sliderTime, BorderLayout.SOUTH );
		frame.pack();
		frame.setDefaultCloseOperation( JFrame.DISPOSE_ON_CLOSE );
		frame.addWindowListener( new WindowAdapter()
		{
			@Override
			public void windowClosing( final WindowEvent e )
			{
				painterThread.interrupt();
			}
		} );
//		frame.addKeyListener( display.getTransformEventHandler() );
//		frame.addKeyListener( sourceSwitcher );
		frame.setVisible( true );

		painterThread.start();

		animatedOverlay = new TextOverlayAnimator( "Press <F1> for help.", 3000, TextPosition.CENTER );
	}

	public void addHandler( final Object handler )
	{
		display.addHandler( handler );
		if ( KeyListener.class.isInstance( handler ) )
			frame.addKeyListener( ( KeyListener ) handler );
	}

	public void getGlobalMouseCoordinates( final RealPositionable gPos )
	{
		assert gPos.numDimensions() == 3;
		final RealPoint lPos = new RealPoint( 3 );
		mouseCoordinates.getMouseCoordinates( lPos );
		viewerTransform.applyInverse( gPos, lPos );
	}

	@Override
	public void paint()
	{
		imageRenderer.paint( state );

		synchronized( this )
		{
			if ( currentAnimator != null )
			{
				final TransformEventHandler3D handler = display.getTransformEventHandler();
				final AffineTransform3D transform = currentAnimator.getCurrent();
				handler.setTransform( transform );
				transformChanged( transform );
				if ( currentAnimator.isComplete() )
					currentAnimator = null;
			}
		}

		display.repaint();
	}

	// TODO remove?
	public void requestRepaint()
	{
		imageRenderer.requestRepaint();
	}

	TextOverlayAnimator animatedOverlay = null;

	@Override
	public void drawOverlays( final Graphics g )
	{
		multiBoxOverlayRenderer.setViewerState( state );
		multiBoxOverlayRenderer.updateVirtualScreenSize( display.getWidth(), display.getHeight() );
		multiBoxOverlayRenderer.paint( ( Graphics2D ) g );

		sourceInfoOverlayRenderer.setViewerState( state );
		sourceInfoOverlayRenderer.paint( ( Graphics2D ) g );

		final RealPoint gPos = new RealPoint( 3 );
		getGlobalMouseCoordinates( gPos );
		final String mousePosGlobalString = String.format( "(%6.1f,%6.1f,%6.1f)", gPos.getDoublePosition( 0 ), gPos.getDoublePosition( 1 ), gPos.getDoublePosition( 2 ) );

		g.setFont( new Font( "Monospaced", Font.PLAIN, 12 ) );
		g.drawString( mousePosGlobalString, ( int ) g.getClipBounds().getWidth() - 170, 25 );

		if ( animatedOverlay != null )
		{
			animatedOverlay.paint( ( Graphics2D ) g );
			if ( animatedOverlay.isComplete() )
				animatedOverlay = null;
			else
				display.repaint();
		}
		if ( multiBoxOverlayRenderer.isHighlightInProgress() )
			display.repaint();
	}

	@Override
	public synchronized void transformChanged( final AffineTransform3D transform )
	{
		viewerTransform.set( transform );
		state.setViewerTransform( transform );
		requestRepaint();
	}

	RotationAnimator currentAnimator = null;

	static enum AlignPlane
	{
		XY,
		ZY,
		XZ
	}

	private final static double c = Math.cos( Math.PI / 4 );
	private final static double[] qAlignXY = new double[] { 1,  0,  0, 0 };
	private final static double[] qAlignZY = new double[] { c,  0, -c, 0 };
	private final static double[] qAlignXZ = new double[] { c,  c,  0, 0 };

	protected synchronized void align( final AlignPlane plane )
	{
		final SpimSourceState< ? > source = state.getSources().get( state.getCurrentSource() );
		final AffineTransform3D sourceTransform = source.getSpimSource().getSourceTransform( state.getCurrentTimepoint(), 0 );

		final double[] qSource = new double[ 4 ];
		RotationAnimator.extractRotationAnisotropic( sourceTransform, qSource );

		final double[] qTmpSource;
		if ( plane == AlignPlane.XY )
			qTmpSource = qSource;
		else
		{
			qTmpSource = new double[4];
			if ( plane == AlignPlane.ZY )
				LinAlgHelpers.quaternionMultiply( qSource, qAlignZY, qTmpSource );
			else // if ( plane == AlignPlane.XZ )
				LinAlgHelpers.quaternionMultiply( qSource, qAlignXZ, qTmpSource );
		}

		final double[] qTarget = new double[ 4 ];
		LinAlgHelpers.quaternionInvert( qTmpSource, qTarget );

		final AffineTransform3D transform = display.getTransformEventHandler().getTransform();
		currentAnimator = new RotationAnimator( transform, mouseCoordinates.getX(), mouseCoordinates.getY(), qTarget, 300 );
		transformChanged( transform );
	}

	protected synchronized void updateTimepoint( final int timepoint )
	{
		if ( state.getCurrentTimepoint() != timepoint )
		{
			state.setCurrentTimepoint( timepoint );
			requestRepaint();
		}
	}

	final int indicatorTime = 800;

	protected synchronized void toggleInterpolation()
	{
		final Interpolation interpolation = state.getInterpolation();
		if ( interpolation == Interpolation.NEARESTNEIGHBOR )
		{
			state.setInterpolation( Interpolation.NLINEAR );
			animatedOverlay = new TextOverlayAnimator( "tri-linear interpolation", indicatorTime );
		}
		else
		{
			state.setInterpolation( Interpolation.NEARESTNEIGHBOR );
			animatedOverlay = new TextOverlayAnimator( "nearest-neighbor interpolation", indicatorTime );
		}
		requestRepaint();
	}

	public synchronized void toggleSingleSourceMode()
	{
		final boolean singleSourceMode = ! state.isSingleSourceMode();
		state.setSingleSourceMode( singleSourceMode );
		animatedOverlay = new TextOverlayAnimator( singleSourceMode ? "single-source mode" : "fused mode", indicatorTime );
		requestRepaint();
	}

	public synchronized void toggleVisibility( final int sourceIndex )
	{
		if ( sourceIndex >= 0 && sourceIndex < state.numSources() )
		{
			final SpimSourceState< ? > source = state.getSources().get( sourceIndex );
			source.setActive( !source.isActive() );
			multiBoxOverlayRenderer.highlight( sourceIndex );
			if ( ! state.isSingleSourceMode() )
				requestRepaint();
			else
				display.repaint();
		}
	}

	/**
	 * Set the index of the source to display.
	 */
	public synchronized void setCurrentSource( final int sourceIndex )
	{
		if ( sourceIndex >= 0 && sourceIndex < state.numSources() )
		{
			state.setCurrentSource( sourceIndex );
			multiBoxOverlayRenderer.highlight( sourceIndex );
			if ( state.isSingleSourceMode() )
				requestRepaint();
			else
				display.repaint();
		}
	}

	protected class SourceSwitcher extends KeyAdapter
	{
		@Override
		public void keyPressed( final KeyEvent e )
		{
			final int keyCode = e.getKeyCode();
			final int modifiers = e.getModifiersEx();
			final boolean toggle = ( modifiers & KeyEvent.SHIFT_DOWN_MASK ) != 0;
			final boolean align = toggle;

			if ( keyCode == KeyEvent.VK_I )
				toggleInterpolation();
			else if ( keyCode == KeyEvent.VK_F )
				toggleSingleSourceMode();
			else if ( keyCode == KeyEvent.VK_1 )
				selectOrToggleSource( 0, toggle );
			else if ( keyCode == KeyEvent.VK_2 )
				selectOrToggleSource( 1, toggle );
			else if ( keyCode == KeyEvent.VK_3 )
				selectOrToggleSource( 2, toggle );
			else if ( keyCode == KeyEvent.VK_4 )
				selectOrToggleSource( 3, toggle );
			else if ( keyCode == KeyEvent.VK_5 )
				selectOrToggleSource( 4, toggle );
			else if ( keyCode == KeyEvent.VK_6 )
				selectOrToggleSource( 5, toggle );
			else if ( keyCode == KeyEvent.VK_7 )
				selectOrToggleSource( 6, toggle );
			else if ( keyCode == KeyEvent.VK_8 )
				selectOrToggleSource( 7, toggle );
			else if ( keyCode == KeyEvent.VK_9 )
				selectOrToggleSource( 8, toggle );
			else if ( keyCode == KeyEvent.VK_0 )
				selectOrToggleSource( 9, toggle );
			else if ( keyCode == KeyEvent.VK_Z && align )
				align( AlignPlane.XY );
			else if ( keyCode == KeyEvent.VK_X && align )
				align( AlignPlane.ZY );
			else if ( ( keyCode == KeyEvent.VK_Y || keyCode == KeyEvent.VK_A ) && align )
				align( AlignPlane.XZ );
		}

		protected void selectOrToggleSource( final int sourceIndex, final boolean toggle )
		{
			if( toggle )
				toggleVisibility( sourceIndex );
			else
				setCurrentSource( sourceIndex );
		}
	}

	protected class MouseCoordinateListener implements MouseMotionListener
	{
		private int x;

		private int y;

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
	}
}