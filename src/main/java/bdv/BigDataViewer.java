package bdv;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ActionMap;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.filechooser.FileFilter;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import net.imglib2.Volatile;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.display.ScaledARGBConverter;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.VolatileARGBType;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import bdv.export.ProgressWriter;
import bdv.export.ProgressWriterConsole;
import bdv.img.cache.Cache;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.WrapBasicImgLoader;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.HelpDialog;
import bdv.tools.InitializeViewerState;
import bdv.tools.RecordMovieDialog;
import bdv.tools.VisibilityAndGroupingDialog;
import bdv.tools.bookmarks.Bookmarks;
import bdv.tools.bookmarks.BookmarksEditor;
import bdv.tools.brightness.BrightnessDialog;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.tools.brightness.RealARGBColorConverterSetup;
import bdv.tools.brightness.SetupAssignments;
import bdv.tools.crop.CropDialog;
import bdv.tools.transformation.ManualTransformation;
import bdv.tools.transformation.ManualTransformationEditor;
import bdv.tools.transformation.TransformedSource;
import bdv.util.KeyProperties;
import bdv.viewer.NavigationActions;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerFrame;
import bdv.viewer.ViewerPanel;

public class BigDataViewer
{
	protected final ViewerFrame viewerFrame;

	protected final ViewerPanel viewer;

	protected final SetupAssignments setupAssignments;

	protected final ManualTransformation manualTransformation;

	protected final Bookmarks bookmarks;

	protected final BrightnessDialog brightnessDialog;

	protected final CropDialog cropDialog;

	protected final RecordMovieDialog movieDialog;

	protected final VisibilityAndGroupingDialog activeSourcesDialog;

	protected final HelpDialog helpDialog;

	protected final ManualTransformationEditor manualTransformationEditor;

	protected final BookmarksEditor bookmarkEditor;

	protected final JFileChooser fileChooser;

	protected File proposedSettingsFile;

	public void toggleManualTransformation()
	{
		manualTransformationEditor.toggle();
	}

	public void initSetBookmark()
	{
		bookmarkEditor.initSetBookmark();
	}

	public void initGoToBookmark()
	{
		bookmarkEditor.initGoToBookmark();
	}

	public void initGoToBookmarkRotation()
	{
		bookmarkEditor.initGoToBookmarkRotation();
	}

	private static String createSetupName( final BasicViewSetup setup )
	{
		if ( setup.hasName() )
			return setup.getName();

		String name = "";

		final Angle angle = setup.getAttribute( Angle.class );
		if ( angle != null )
			name += ( name.isEmpty() ? "" : " " ) + "a " + angle.getName();

		final Channel channel = setup.getAttribute( Channel.class );
		if ( channel != null )
			name += ( name.isEmpty() ? "" : " " ) + "c " + channel.getName();

		return name;
	}

	private static < T extends RealType< T >, V extends Volatile< T > & RealType< V > > void initSetupsRealType(
			final AbstractSpimData< ? > spimData,
			final T type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		if ( spimData.getSequenceDescription().getImgLoader() instanceof WrapBasicImgLoader )
		{
			initSetupsRealTypeNonVolatile( spimData, type, converterSetups, sources );
			return;
		}
		final double typeMin = Math.max( 0, Math.min( type.getMinValue(), 65535 ) );
		final double typeMax = Math.max( 0, Math.min( type.getMaxValue(), 65535 ) );
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
		{
			final RealARGBColorConverter< V > vconverter = new RealARGBColorConverter.Imp0< V >( typeMin, typeMax );
			vconverter.setColor( new ARGBType( 0xffffffff ) );
			final RealARGBColorConverter< T > converter = new RealARGBColorConverter.Imp1< T >( typeMin, typeMax );
			converter.setColor( new ARGBType( 0xffffffff ) );

			final int setupId = setup.getId();
			final String setupName = createSetupName( setup );
			final VolatileSpimSource< T, V > vs = new VolatileSpimSource< T, V >( spimData, setupId, setupName );
			final SpimSource< T > s = vs.nonVolatile();

			// Decorate each source with an extra transformation, that can be
			// edited manually in this viewer.
			final TransformedSource< V > tvs = new TransformedSource< V >( vs );
			final TransformedSource< T > ts = new TransformedSource< T >( s, tvs );

			final SourceAndConverter< V > vsoc = new SourceAndConverter< V >( tvs, vconverter );
			final SourceAndConverter< T > soc = new SourceAndConverter< T >( ts, converter, vsoc );

			sources.add( soc );
			converterSetups.add( new RealARGBColorConverterSetup( setupId, converter, vconverter ) );
		}
	}

	private static < T extends RealType< T > > void initSetupsRealTypeNonVolatile(
			final AbstractSpimData< ? > spimData,
			final T type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		final double typeMin = type.getMinValue();
		final double typeMax = type.getMaxValue();
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
		{
			final RealARGBColorConverter< T > converter = new RealARGBColorConverter.Imp1< T >( typeMin, typeMax );
			converter.setColor( new ARGBType( 0xffffffff ) );

			final int setupId = setup.getId();
			final String setupName = createSetupName( setup );
			final SpimSource< T > s = new SpimSource< T >( spimData, setupId, setupName );

			// Decorate each source with an extra transformation, that can be
			// edited manually in this viewer.
			final TransformedSource< T > ts = new TransformedSource< T >( s );
			final SourceAndConverter< T > soc = new SourceAndConverter< T >( ts, converter );

			sources.add( soc );
			converterSetups.add( new RealARGBColorConverterSetup( setupId, converter ) );
		}
	}

	private static void initSetupsARGBType(
			final AbstractSpimData< ? > spimData,
			final ARGBType type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		if ( spimData.getSequenceDescription().getImgLoader() instanceof WrapBasicImgLoader )
		{
			initSetupsARGBTypeNonVolatile( spimData, type, converterSetups, sources );
			return;
		}
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
		{
			final ScaledARGBConverter.VolatileARGB vconverter = new ScaledARGBConverter.VolatileARGB( 0, 255 );
			final ScaledARGBConverter.ARGB converter = new ScaledARGBConverter.ARGB( 0, 255 );

			final int setupId = setup.getId();
			final String setupName = createSetupName( setup );
			final VolatileSpimSource< ARGBType, VolatileARGBType > vs = new VolatileSpimSource< ARGBType, VolatileARGBType >( spimData, setupId, setupName );
			final SpimSource< ARGBType > s = vs.nonVolatile();

			// Decorate each source with an extra transformation, that can be
			// edited manually in this viewer.
			final TransformedSource< VolatileARGBType > tvs = new TransformedSource< VolatileARGBType >( vs );
			final TransformedSource< ARGBType > ts = new TransformedSource< ARGBType >( s, tvs );

			final SourceAndConverter< VolatileARGBType > vsoc = new SourceAndConverter< VolatileARGBType >( tvs, vconverter );
			final SourceAndConverter< ARGBType > soc = new SourceAndConverter< ARGBType >( ts, converter, vsoc );

			sources.add( soc );
			converterSetups.add( new RealARGBColorConverterSetup( setupId, converter, vconverter ) );
		}
	}

	private static void initSetupsARGBTypeNonVolatile(
			final AbstractSpimData< ? > spimData,
			final ARGBType type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		for ( final BasicViewSetup setup : seq.getViewSetupsOrdered() )
		{
			final ScaledARGBConverter.ARGB converter = new ScaledARGBConverter.ARGB( 0, 255 );

			final int setupId = setup.getId();
			final String setupName = createSetupName( setup );
			final SpimSource< ARGBType > s = new SpimSource< ARGBType >( spimData, setupId, setupName );

			// Decorate each source with an extra transformation, that can be
			// edited manually in this viewer.
			final TransformedSource< ARGBType > ts = new TransformedSource< ARGBType >( s );
			final SourceAndConverter< ARGBType > soc = new SourceAndConverter< ARGBType >( ts, converter );

			sources.add( soc );
			converterSetups.add( new RealARGBColorConverterSetup( setupId, converter ) );
		}
	}

	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static void initSetups(
			final AbstractSpimData< ? > spimData,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		final Object type = spimData.getSequenceDescription().getImgLoader().getImageType();
		if ( RealType.class.isInstance( type ) )
			initSetupsRealType( spimData, ( RealType ) type, converterSetups, sources );
		else if ( ARGBType.class.isInstance( type ) )
			initSetupsARGBType( spimData, ( ARGBType ) type, converterSetups, sources );
		else
			throw new IllegalArgumentException( "ImgLoader of type " + type.getClass() + " not supported." );
	}

	/**
	 *
	 * @param converterSetups
	 *            list of {@link ConverterSetup} that control min/max and color
	 *            of sources.
	 * @param sources
	 *            list of pairs of source of some type and converter from that
	 *            type to ARGB.
	 * @param spimData
	 *            may be null. The {@link AbstractSpimData} of the dataset (if
	 *            there is one). If it exists, it is used to set up a "Crop"
	 *            dialog.
	 * @param numTimepoints
	 *            the number of timepoints in the dataset.
	 * @param cache
	 *            handle to cache. This is used to control io timing.
	 * @param windowTitle
	 *            title of the viewer window.
	 * @param windowWidth
	 *            width of the viewer window.
	 * @param windowHeight
	 *            height of the viewer window.
	 * @param progressWriter
	 *            a {@link ProgressWriter} to which BDV may report progress
	 *            (currently only used in the "Record Movie" dialog).
	 */
	public BigDataViewer(
			final ArrayList< ConverterSetup > converterSetups,
			final ArrayList< SourceAndConverter< ? > > sources,
			final AbstractSpimData< ? > spimData,
			final int numTimepoints,
			final Cache cache,
			final String windowTitle,
			final int windowWidth,
			final int windowHeight,
			final ProgressWriter progressWriter )
	{
		viewerFrame = new ViewerFrame( windowWidth, windowHeight, sources, numTimepoints, cache );
		if ( windowTitle != null )
			viewerFrame.setTitle( windowTitle );
		viewer = viewerFrame.getViewerPanel();

		for ( final ConverterSetup cs : converterSetups )
			if ( RealARGBColorConverterSetup.class.isInstance( cs ) )
				( ( RealARGBColorConverterSetup ) cs ).setViewer( viewer );

		manualTransformation = new ManualTransformation( viewer );
		manualTransformationEditor = new ManualTransformationEditor( viewer, viewerFrame.getKeybindings() );

		bookmarks = new Bookmarks();
		bookmarkEditor = new BookmarksEditor( viewer, viewerFrame.getKeybindings(), bookmarks );

		setupAssignments = new SetupAssignments( converterSetups, 0, 65535 );
		if ( setupAssignments.getMinMaxGroups().size() > 0 )
		{
			final MinMaxGroup group = setupAssignments.getMinMaxGroups().get( 0 );
			for ( final ConverterSetup setup : setupAssignments.getConverterSetups() )
				setupAssignments.moveSetupToGroup( setup, group );
		}

		brightnessDialog = new BrightnessDialog( viewerFrame, setupAssignments );

		cropDialog = ( spimData == null ) ? null : new CropDialog( viewerFrame, viewer, spimData.getSequenceDescription() );

		movieDialog = new RecordMovieDialog( viewerFrame, viewer, progressWriter );
		// this is just to get updates of window size:
		viewer.getDisplay().addOverlayRenderer( movieDialog );

		activeSourcesDialog = new VisibilityAndGroupingDialog( viewerFrame, viewer.getVisibilityAndGrouping() );

		helpDialog = new HelpDialog( viewerFrame );

		fileChooser = new JFileChooser();
		fileChooser.setFileFilter( new FileFilter()
		{
			@Override
			public String getDescription()
			{
				return "xml files";
			}

			@Override
			public boolean accept( final File f )
			{
				if ( f.isDirectory() )
					return true;
				if ( f.isFile() )
				{
					final String s = f.getName();
					final int i = s.lastIndexOf( '.' );
					if ( i > 0 && i < s.length() - 1 )
					{
						final String ext = s.substring( i + 1 ).toLowerCase();
						return ext.equals( "xml" );
					}
				}
				return false;
			}
		} );

		final KeyProperties keyProperties = KeyProperties.readPropertyFile();
		NavigationActions.installActionBindings( viewerFrame.getKeybindings(), viewer, keyProperties );
		BigDataViewerActions.installActionBindings( viewerFrame.getKeybindings(), this, keyProperties );

		final JMenuBar menubar = new JMenuBar();
		JMenu menu = new JMenu( "File" );
		menubar.add( menu );

		final ActionMap actionMap = viewerFrame.getKeybindings().getConcatenatedActionMap();
		final JMenuItem miLoadSettings = new JMenuItem( actionMap.get( BigDataViewerActions.LOAD_SETTINGS ) );
		miLoadSettings.setText( "Load settings" );
		menu.add( miLoadSettings );

		final JMenuItem miSaveSettings = new JMenuItem( actionMap.get( BigDataViewerActions.SAVE_SETTINGS ) );
		miSaveSettings.setText( "Save settings" );
		menu.add( miSaveSettings );

		menu = new JMenu( "Settings" );
		menubar.add( menu );

		final JMenuItem miBrightness = new JMenuItem( actionMap.get( BigDataViewerActions.BRIGHTNESS_SETTINGS ) );
		miBrightness.setText( "Brightness & Color" );
		menu.add( miBrightness );

		final JMenuItem miVisibility = new JMenuItem( actionMap.get( BigDataViewerActions.VISIBILITY_AND_GROUPING ) );
		miVisibility.setText( "Visibility & Grouping" );
		menu.add( miVisibility );

		menu = new JMenu( "Tools" );
		menubar.add( menu );

		if ( cropDialog != null )
		{
			final JMenuItem miCrop = new JMenuItem( actionMap.get( BigDataViewerActions.CROP ) );
			miCrop.setText( "Crop" );
			menu.add( miCrop );
		}

		final JMenuItem miMovie = new JMenuItem( actionMap.get( BigDataViewerActions.RECORD_MOVIE ) );
		miMovie.setText( "Record Movie" );
		menu.add( miMovie );

		final JMenuItem miManualTransform = new JMenuItem( actionMap.get( BigDataViewerActions.MANUAL_TRANSFORM ) );
		miManualTransform.setText( "Manual Transform" );
		menu.add( miManualTransform );

		menu = new JMenu( "Help" );
		menubar.add( menu );

		final JMenuItem miHelp = new JMenuItem( actionMap.get( BigDataViewerActions.SHOW_HELP ) );
		miHelp.setText( "Show Help" );
		menu.add( miHelp );

		viewerFrame.setJMenuBar( menubar );
	}

	public static BigDataViewer open( final AbstractSpimData< ? > spimData, final String windowTitle, final ProgressWriter progressWriter )
	{
		final int width = 800;
		final int height = 600;

		if ( WrapBasicImgLoader.wrapImgLoaderIfNecessary( spimData ) )
		{
			System.err.println( "WARNING:\nOpening <SpimData> dataset that is not suited for interactive browsing.\nConsider resaving as HDF5 for better performance." );
		}

		final ArrayList< ConverterSetup > converterSetups = new ArrayList< ConverterSetup >();
		final ArrayList< SourceAndConverter< ? > > sources = new ArrayList< SourceAndConverter< ? > >();
		initSetups( spimData, converterSetups, sources );

		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		final int numTimepoints = seq.getTimePoints().size();
		final Cache cache = ( ( ViewerImgLoader< ?, ? > ) seq.getImgLoader() ).getCache();

		final BigDataViewer bdv = new BigDataViewer( converterSetups, sources, spimData, numTimepoints, cache, windowTitle, width, height, progressWriter );

		WrapBasicImgLoader.removeWrapperIfPresent( spimData );

		bdv.viewerFrame.setVisible( true );
		InitializeViewerState.initTransform( bdv.viewer );
		return bdv;
	}

	public static BigDataViewer open( final String xmlFilename, final String windowTitle, final ProgressWriter progressWriter ) throws SpimDataException
	{
		final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( xmlFilename );
		final BigDataViewer bdv = open ( spimData, windowTitle, progressWriter );
		if ( !bdv.tryLoadSettings( xmlFilename ) )
			InitializeViewerState.initBrightness( 0.001, 0.999, bdv.viewer, bdv.setupAssignments );
		return bdv;
	}

	public static BigDataViewer open(
			final ArrayList< ConverterSetup > converterSetups,
			final ArrayList< SourceAndConverter< ? > > sources,
			final int numTimepoints,
			final Cache cache,
			final String windowTitle,
			final ProgressWriter progressWriter )
	{
		final BigDataViewer bdv = new BigDataViewer( converterSetups, sources, null, numTimepoints, cache, windowTitle, 800, 600, progressWriter );
		bdv.viewerFrame.setVisible( true );
		InitializeViewerState.initTransform( bdv.viewer );
		return bdv;
	}

	public ViewerPanel getViewer()
	{
		return viewer;
	}

	public ViewerFrame getViewerFrame()
	{
		return viewerFrame;
	}

	public SetupAssignments getSetupAssignments()
	{
		return setupAssignments;
	}

	protected boolean tryLoadSettings( final String xmlFilename )
	{
		proposedSettingsFile = null;
		if ( xmlFilename.startsWith( "http://" ) || xmlFilename.startsWith( "https://" ) )
		{
			// load settings.xml from the BigDataServer
			final String settings = xmlFilename + "settings";
			{
				try
				{
					loadSettings( settings );
					return true;
				}
				catch ( final FileNotFoundException e )
				{}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}
			}
		}
		else if ( xmlFilename.endsWith( ".xml" ) )
		{
			final String settings = xmlFilename.substring( 0, xmlFilename.length() - ".xml".length() ) + ".settings" + ".xml";
			proposedSettingsFile = new File( settings );
			if ( proposedSettingsFile.isFile() )
			{
				try
				{
					loadSettings( settings );
					return true;
				}
				catch ( final Exception e )
				{
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	protected void saveSettings()
	{
		fileChooser.setSelectedFile( proposedSettingsFile );
		final int returnVal = fileChooser.showSaveDialog( null );
		if ( returnVal == JFileChooser.APPROVE_OPTION )
		{
			proposedSettingsFile = fileChooser.getSelectedFile();
			try
			{
				saveSettings( proposedSettingsFile.getCanonicalPath() );
			}
			catch ( final IOException e )
			{
				e.printStackTrace();
			}
		}
	}

	protected void saveSettings( final String xmlFilename ) throws IOException
	{
		final Element root = new Element( "Settings" );
		root.addContent( viewer.stateToXml() );
		root.addContent( setupAssignments.toXml() );
		root.addContent( manualTransformation.toXml() );
		root.addContent( bookmarks.toXml() );
		final Document doc = new Document( root );
		final XMLOutputter xout = new XMLOutputter( Format.getPrettyFormat() );
		xout.output( doc, new FileWriter( xmlFilename ) );
	}

	protected void loadSettings()
	{
		fileChooser.setSelectedFile( proposedSettingsFile );
		final int returnVal = fileChooser.showOpenDialog( null );
		if ( returnVal == JFileChooser.APPROVE_OPTION )
		{
			proposedSettingsFile = fileChooser.getSelectedFile();
			try
			{
				loadSettings( proposedSettingsFile.getCanonicalPath() );
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}
	}

	protected void loadSettings( final String xmlFilename ) throws IOException, JDOMException
	{
		final SAXBuilder sax = new SAXBuilder();
		final Document doc = sax.build( xmlFilename );
		final Element root = doc.getRootElement();
		viewer.stateFromXml( root );
		setupAssignments.restoreFromXml( root );
		manualTransformation.restoreFromXml( root );
		bookmarks.restoreFromXml( root );
		activeSourcesDialog.update();
		viewer.requestRepaint();
	}

	/**
	 * Deprecated, please use {@link #open(String, String, ProgressWriter)} instead.
	 */
	@Deprecated
	public BigDataViewer( final String xmlFilename, final String windowTitle, final ProgressWriter progressWriter ) throws SpimDataException
	{
		this( new XmlIoSpimDataMinimal().load( xmlFilename ), windowTitle, progressWriter );
		if ( !tryLoadSettings( xmlFilename ) )
			InitializeViewerState.initBrightness( 0.001, 0.999, viewer, setupAssignments );
	}

	/**
	 * Deprecated, please use {@link #open(AbstractSpimData, String, ProgressWriter)} instead.
	 */
	@Deprecated
	public BigDataViewer( final AbstractSpimData< ? > spimData, final String windowTitle, final ProgressWriter progressWriter )
	{
		this( new ForDeprecatedConstructors( spimData, windowTitle, progressWriter ) );
		viewerFrame.setVisible( true );
		InitializeViewerState.initTransform( viewer );
	}

	@Deprecated
	private static class ForDeprecatedConstructors
	{
		final ArrayList< ConverterSetup > converterSetups;
		final ArrayList< SourceAndConverter< ? > > sources;
		final AbstractSpimData< ? > spimData;
		final int numTimepoints;
		final Cache cache;
		final String windowTitle;
		final int windowWidth;
		final int windowHeight;
		final ProgressWriter progressWriter;

		private ForDeprecatedConstructors( final AbstractSpimData< ? > spimData, final String windowTitle, final ProgressWriter progressWriter )
		{
			this.windowTitle = windowTitle;
			this.progressWriter = progressWriter;
			this.spimData = spimData;

			windowWidth = 800;
			windowHeight = 600;

			if ( WrapBasicImgLoader.wrapImgLoaderIfNecessary( spimData ) )
			{
				System.err.println( "WARNING:\nOpening <SpimData> dataset that is not suited for interactive browsing.\nConsider resaving as HDF5 for better performance." );
			}

			converterSetups = new ArrayList< ConverterSetup >();
			sources = new ArrayList< SourceAndConverter< ? > >();
			initSetups( spimData, converterSetups, sources );

			final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
			numTimepoints = seq.getTimePoints().size();
			cache = ( ( ViewerImgLoader< ?, ? > ) seq.getImgLoader() ).getCache();

			WrapBasicImgLoader.removeWrapperIfPresent( spimData );
		}
	}

	@Deprecated
	private BigDataViewer( final ForDeprecatedConstructors p )
	{
		this( p.converterSetups, p.sources, p.spimData, p.numTimepoints, p.cache, p.windowTitle, p.windowWidth, p.windowHeight, p.progressWriter );
	}

	/**
	 * Deprecated, please use {@link #open(String, String, ProgressWriter)} instead.
	 */
	@Deprecated
	public static void view( final String filename, final ProgressWriter progressWriter ) throws SpimDataException
	{
		view( filename, new File( filename ).getName(), progressWriter );
	}

	/**
	 * Deprecated, please use {@link #open(String, String, ProgressWriter)} instead.
	 */
	@Deprecated
	public static void view( final String title, final String filename, final ProgressWriter progressWriter ) throws SpimDataException
	{
		open( filename, title, progressWriter );
	}

	public static void main( final String[] args )
	{
//		final String fn = "http://tomancak-mac-17.mpi-cbg.de:8080/openspim/";
//		final String fn = "/Users/Pietzsch/Desktop/openspim/datasetHDF.xml";
		final String fn = "/Users/pietzsch/workspace/data/111010_weber_full.xml";
//		final String fn = "/Users/Pietzsch/Desktop/spimrec2/dataset.xml";
//		final String fn = "/Users/pietzsch/Desktop/HisYFP-SPIM/dataset.xml";
//		final String fn = "/Users/Pietzsch/Desktop/bdv example/drosophila 2.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/clusterValia/140219-1/valia-140219-1.xml";
//		final String fn = "/Users/Pietzsch/Desktop/data/catmaid.xml";
//		final String fn = "src/main/resources/openconnectome-bock11-neariso.xml";
//		final String fn = "/Users/Pietzsch/Desktop/data/catmaid-confocal.xml";
//		final String fn = "/Users/pietzsch/desktop/data/BDV130418A325/BDV130418A325_NoTempReg.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/valia2/valia.xml";
//		final String fn = "/Users/pietzsch/workspace/data/fast fly/111010_weber/combined.xml";
//		final String fn = "/Users/pietzsch/workspace/data/mette/mette.xml";
//		final String fn = "/Users/tobias/Desktop/openspim.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/fibsem.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/fibsem-remote.xml";
//		final String fn = "/Users/pietzsch/Desktop/url-valia.xml";
//		final String fn = "/Users/pietzsch/Desktop/data/clusterValia/140219-1/valia-140219-1.xml";
//		final String fn = "/Users/pietzsch/workspace/data/111010_weber_full.xml";
//		final String fn = "/Volumes/projects/tomancak_lightsheet/Mette/ZeissZ1SPIM/Maritigrella/021013_McH2BsGFP_CAAX-mCherry/11-use/hdf5/021013_McH2BsGFP_CAAX-mCherry-11-use.xml";
		try
		{
			System.setProperty( "apple.laf.useScreenMenuBar", "true" );
			open( fn, new File( fn ).getName(), new ProgressWriterConsole() );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
		}
	}
}
