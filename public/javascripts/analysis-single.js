var Analyst = Analyst || {};

(function(A, $) {

	A.analysis.AnalysisSinglePointLayout = Backbone.Marionette.Layout.extend({

		template: Handlebars.getTemplate('analysis', 'analysis-single-point'),

		events: {
		  'change #scenarioComparison': 'selectComparisonType',
		  'change #scenario1': 'createSurface',
		  'change #scenario2': 'createSurface',
		  'change #shapefile': 'createSurface',
			'cahnge .timesel': 'createSurface',
			'change #shapefileColumn': 'updateCharts',
		  'change #chartType' : 'updateResults',
			'change .which input' : 'updateEnvelope',
			'change #shapefile' : 'updateAttributes',
		  'click #showIso': 'updateMap',
		  'click #showPoints': 'updateMap',
		  'click #showTransit': 'updateMap',
		  'click .mode-selector' : 'updateMap',
			'click #showSettings' : 'showSettings',
			'click #downloadGis' : 'downloadGis'
		},

		regions: {
			analysisDetail: '#detail'
		},

		initialize: function(options){
			_.bindAll(this, 'createSurface', 'updateMap', 'onMapClick', 'updateEnvelope', 'updateAttributes', 'updateCharts');

			this.transitOverlays = {};
		},

		isochroneStyle: function(seconds) {
		    var style = {
		      color: '#333',
		      fillColor: '#333',
		      lineCap: 'round',
		      lineJoin: 'round',
		      weight: 1.5,
		      dashArray: '5, 5',
		      fillOpacity: '0.05'
		    };
		    if (seconds == 3600) {
		      style.weight = 1.5;
		    } else {
		      style.weight = 1.5;
		    }
		    return style;
		},

		/**
		 * Update the best case, worst case, etc. that the user can choose
		 * based on the modes. For example, for on-street modes, there is no
		 * best or worst case; for transit modes, there is currently no point estimate.
		 *
		 * This function also turns on or off the latest departure time option.
		 */
		updateAvailableEnvelopeParameters: function() {
		  // we use this variable so that the map is not automatically redrawn when
		  // we check checkboxes programatically
		  this.envelopeParametersChangingProgramatically = true;

		  var mode = this.mode;
		  var inps = this.$('.which');

		  if (A.util.isTransit(mode)) {
		    // transit request, we're doing profile routing
		    inps.find('[value="WORST_CASE"]').prop('disabled', false).parent().removeClass('hidden');
		    inps.find('[value="BEST_CASE"]').prop('disabled', false).parent().removeClass('hidden');
				inps.find('[value="AVERAGE"]').prop('disabled', true).parent().removeClass('hidden');
		    inps.find('[value="SPREAD"]').prop('disabled', true).parent().addClass('hidden');
		    inps.find('[value="POINT_ESTIMATE"]').prop('disabled', true).parent().addClass('hidden');

		    if (inps.find(':checked:disabled').length > 0 || inps.find(':checked').length == 0) {
		      // we have disabled the currently selected envelope parameter, choose a reasonable default
		      inps.find('input').prop('checked', false).parent().removeClass('active');
		      inps.find('[value="AVERAGE"]').prop('checked', true).parent().addClass('active');
		    }

				this.$('#toTimeControls').removeClass('hidden');
		  } else {
		    // non-transit request, we're doing vanilla routing with point estimates only
		    inps.find('[value="WORST_CASE"]').prop('disabled', true).parent().addClass('hidden');
		    inps.find('[value="BEST_CASE"]').prop('disabled', true).parent().addClass('hidden');
				inps.find('[value="AVERAGE"]').prop('disabled', true).parent().addClass('hidden');
		    inps.find('[value="SPREAD"]').prop('disabled', true).parent().addClass('hidden');

		    // since there is only one option, we may as well go ahead and check it
		    inps.find('[value="POINT_ESTIMATE"]')
		      .prop('disabled', false)
		      .prop('checked', true)
		      .parent()
		      .removeClass('hidden')
		      .addClass('active');

				this.$('#toTimeControls').addClass('hidden');
		  }

		  this.envelopeParametersChangingProgramatically = false;
		},


		/**
		 * Event handler to update the envelope parameters
		 */
		updateEnvelope : function (e) {
			// prevent it from being run twice: once for uncheck and once for check
			if (e.target.checked && this.envelopeParametersChangingProgramatically !== true) {
				this.createSurface();
			}
		},

		onShow : function() {

			var _this = this;

			this.$('#date').datetimepicker({pickTime: false})
				.on('dp.hide', this.createSurface);
			this.$('#fromTime').datetimepicker({pickDate: false})
				.on('dp.hide', this.createSurface);
			this.$('#toTime').datetimepicker({pickDate: false})
				.on('dp.hide', this.createSurface);

			// pick a reasonable default date
			$.get('api/project/' + A.app.selectedProject + '/exemplarDay')
			.done(function (data) {
				var $d = _this.$('#date');

				var sp = data.split('-');
				// months are off by one in javascript
				var date = new Date(sp[0], sp[1] - 1, sp[2]);

				_this.$('#date').data('DateTimePicker').setDate(date);
			});

			// set default times
			this.$('#fromTime').data('DateTimePicker').setDate(new Date(2014, 11, 15, 7, 0, 0));
			this.$('#toTime')  .data('DateTimePicker').setDate(new Date(2014, 11, 15, 9, 0, 0));

			this.$('#scenario2-controls').hide();

			if(A.map.tileOverlay && A.map.hasLayer(A.map.tileOverlay))
		  		A.map.removeLayer(A.map.tileOverlay);


			if(A.map.marker && A.map.hasLayer(A.map.marker))
	  			A.map.removeLayer(A.map.marker);

	  		A.map.on('click', this.onMapClick);

			this.shapefiles = new A.models.Shapefiles();
			this.scenarios = new A.models.Scenarios();

			this.timeSlider = this.$('#timeSlider1').slider({
					formater: function(value) {
						return value + " minutes";
					}
				}).on('slideStop', function(evt) {

					_this.$('#minTimeValue').html(evt.value[0] + "");
					_this.$('#timeLimitValue').html(evt.value[1] + " mins");

				_this.updateResults(true)
				_this.updateMap();
			}).data('slider');

			this.$('#minTimeValue').html("0");
			this.$('#timeLimitValue').html("60 mins");

			this.walkSpeedSlider = this.$('#walkSpeedSlider').slider({
					formater: function(value) {
						_this.$('#walkSpeedValue').html(value + " km/h");
						return value + " km/h";
					}
				}).on('slideStop', function(value) {

				_this.createSurface();
			}).data('slider');

			this.bikeSpeedSlider = this.$('#bikeSpeedSlider').slider({
					formater: function(value) {
						_this.$('#bikeSpeedValue').html(value + " km/h");
						return value + " km/h";
					}
				}).on('slideStop', function(value) {

				_this.createSurface();
			}).data('slider');

			this.mode = 'TRANSIT,WALK';

			this.updateAvailableEnvelopeParameters();

		    this.$('input[name=mode]:radio').on('change', function(event) {
				_this.mode = _this.$('input:radio[name=mode]:checked').val();
				_this.updateAvailableEnvelopeParameters();
				_this.createSurface();
		    });

			this.shapefiles.fetch({reset: true, data : {projectId: A.app.selectedProject}})
				.done(function () {
				_this.$("#primaryIndicator").empty();

				_this.shapefiles.each(function (shp) {
					$('<option>')
						.attr('value', shp.id)
						.text(shp.get('name'))
						.appendTo(this.$('#shapefile'));
				});

				_this.updateAttributes();
			});

			this.scenarios.fetch({reset: true, data : {projectId: A.app.selectedProject}, success: function(collection, response, options){

				_this.$(".scenario-list").empty();

				for(var i in _this.scenarios.models) {
					if(_this.scenarios.models[i].get("id") == "default")
						_this.$(".scenario-list").append('<option selected value="' + _this.scenarios.models[i].get("id") + '">' + _this.scenarios.models[i].get("name") + '</option>');
					else
						_this.$(".scenario-list").append('<option value="' + _this.scenarios.models[i].get("id") + '">' + _this.scenarios.models[i].get("name") + '</option>');

				}

			}});

			this.$('#comparisonChart').hide();
			this.$('#compareLegend').hide();

			this.$('#queryProcessing').hide();
			this.$('#showSettings').hide();
			this.$('#queryResults').hide();

		},

		/**
		* Update the attributes select to show the attributes of the current shapefile
		*/
		updateAttributes: function () {
			var shpId = this.$('#shapefile').val();
			var shp = this.shapefiles.get(shpId);
			var _this = this;

			this.$('#shapefileColumn').empty();

			shp.getNumericAttributes().forEach(function (attr) {
				var atName = A.models.Shapefile.attributeName(attr);

				if(!attr.hide) {
					$('<option>')
					.attr('value', attr.fieldName)
					.text(atName)
					.appendTo(_this.$('#shapefileColumn'));
				}
			});
		},

		onClose : function() {
			if(A.map.tileOverlay && A.map.hasLayer(A.map.tileOverlay))
		  		A.map.removeLayer(A.map.tileOverlay);

		  	if(A.map.marker && A.map.hasLayer(A.map.marker))
		  		A.map.removeLayer(A.map.marker);

		  	if(A.map.isochronesLayer  && A.map.hasLayer(A.map.isochronesLayer))
		  		A.map.removeLayer(A.map.isochronesLayer);


			for(var id in this.transitOverlays){
				if(this.transitOverlays[id] && A.map.hasLayer(this.transitOverlays[id]))
					A.map.removeLayer(this.transitOverlays[id]);
			}

		  	A.map.off('click', this.onMapClick);

		  	A.map.marker = false;

		 },

		createSurface : function() {

			if(!A.map.marker)
				return;

			if(A.map.tileOverlay && A.map.hasLayer(A.map.tileOverlay))
		  		A.map.removeLayer(A.map.tileOverlay);

		  	this.surfaceId1 = false;
		  	this.surfaceId2 = false;

		  	this.barChart1 = false;
		  	this.barChart2 = false;

		  	this.scenario1Data = false;
		  	this.scenario2Data = false;

		  	if(this.comparisonType == 'compare') {
		  		this.$('#comparisonChart').show();
		  		this.$('#compareLegend').show();
		  		this.$('#legend').hide();
		  	}
		  	else {
		  		this.$('#comparisonChart').hide();
		  		this.$('#compareLegend').hide();
		  		this.$('#legend').show();
		  	}


		  	var bikeSpeed = (this.bikeSpeedSlider.getValue() * 1000 / 60 / 60 );
		  	var walkSpeed = (this.walkSpeedSlider.getValue() * 1000 / 60 / 60 );

 			var graphId1 = this.$('#scenario1').val();
			var which = this.$('input[name="which"]:checked').val();

			var _this = this;

			this.$('#querySettings').hide();
			this.$('#showSettings').show();
			this.$('#queryResults').hide();
			this.$('#queryProcessing').show();

			var date = this.$('#date').data('DateTimePicker').getDate().format('YYYY-MM-DD');
			var fromTime = A.util.makeTime(this.$('#fromTime').data('DateTimePicker').getDate());

			var dateTime = '&date=' + date + '&fromTime=' + fromTime;

			if (A.util.isTransit(this.mode))
				dateTime += '&toTime=' + A.util.makeTime(this.$('#toTime').data('DateTimePicker').getDate());

			var surfaceUrl1 = '/api/surface?graphId=' + graphId1 + '&lat=' + A.map.marker.getLatLng().lat + '&lon=' +
				A.map.marker.getLatLng().lng + '&mode=' + this.mode + '&bikeSpeed=' + bikeSpeed + '&walkSpeed=' + walkSpeed +
				'&which=' + which + dateTime;

		    $.getJSON(surfaceUrl1, function(data) {

		  	  _this.surfaceId1 = data.id;

		  	  if(!this.comparisonType || _this.surfaceId2) {
		  	 	_this.updateMap();
		  	 	_this.updateResults();
		  	  }

		    });

		    if(this.comparisonType == 'compare') {

		    	var graphId2 = this.$('#scenario2').val();
					var which = this.$('input[name="which"]:checked').val();

		    	var surfaceUrl2 = '/api/surface?graphId=' + graphId2 + '&lat=' + A.map.marker.getLatLng().lat + '&lon=' +
						A.map.marker.getLatLng().lng + '&mode=' + this.mode + '&bikeSpeed=' + bikeSpeed +
						'&walkSpeed=' + walkSpeed + '&which=' + which + dateTime;
		    	$.getJSON(surfaceUrl2, function(data) {

			  	  _this.surfaceId2 = data.id;

			  	  if(_this.surfaceId1) {
			  	  	_this.updateMap();
			  	  	_this.updateResults();
			  	  }

			    });

		    }
		},

		updateResults : function (timeUpdateOnly) {

			var _this = this;

			this.comparisonType = this.$('.scenario-comparison').val();

			this.$('#queryProcessing').hide();
			this.$('#queryResults').show();

			var df, df1, df2;
			df = df1 = df2 = null;

			if(this.comparisonType == 'compare') {
				var res1Url = '/api/result?shapefileId=' + this.$("#shapefile").val() +
					'&surfaceId=' + this.surfaceId1 +	'&which=' + this.$('input[name="which"]:checked').val();

				df1 = $.get(res1Url);
				df1.then(function (res) {
					_this.scenario1Data = res;
				});

				var res2Url = '/api/result?shapefileId=' + this.$("#shapefile").val() +
				  '&surfaceId=' + this.surfaceId2 + '&which=' + this.$('input[name="which"]:checked').val();

				df2 = $.get(res2Url);
				df2.then(function(res) {
					_this.scenario2Data = res;
				});

				df = $.when(df1, df2);
			}
			else {
				var resUrl = '/api/result?shapefileId=' + this.$("#shapefile").val() +
					'&surfaceId=' + this.surfaceId1 +	'&which=' + this.$('input[name="which"]:checked').val();
				df = $.get(resUrl)
				df.then(function(res) {
					_this.scenario1Data = res;
					_this.scenario2Data = null;
				});
			}

			df.then(function () {
				_this.updateCharts();
			});
		},

		/**
		 * Draw the charts
		 */
		updateCharts: function () {
			var categoryId = this.shapefiles.get(this.$("#shapefile").val()).get('categoryId');
			var attributeId = this.$('#shapefileColumn').val()

			// reset the maximum value in case it has changed.
			this.maxChartValue = 0;

			this.drawChart(this.scenario1Data, categoryId + '.' + attributeId, 1, '#barChart1', 175);

			if (this.scenario2Data) {
				this.drawChart(this.scenario1Data, categoryId + '.' + attributeId, 2, '#barChart2', 175);
			}
		},

		updateMap : function() {
			var _this = this;

			var showTransit =  this.$('#showTransit').prop('checked');

			this.comparisonType = this.$('.scenario-comparison').val();

			if(showTransit) {
				if(this.comparisonType == 'compare') {

					var scenarioId1 = this.$('#scenario1').val();

					if(A.map.hasLayer(this.transitOverlays[scenarioId1]))
			 			A.map.removeLayer(this.transitOverlays[scenarioId1]);

					this.transitOverlays[scenarioId1] = L.tileLayer('/tile/transit?z={z}&x={x}&y={y}&scenarioId=' + scenarioId1).addTo(A.map);

					var scenarioId2 = this.$('#scenario2').val();

					var compareKey = scenarioId1 + "_ " + scenarioId2;

					if(A.map.hasLayer(this.transitOverlays[compareKey]))
			 			A.map.removeLayer(this.transitOverlays[compareKey]);

					this.transitOverlays[compareKey] = L.tileLayer('/tile/transitComparison?z={z}&x={x}&y={y}&scenarioId1=' + scenarioId1 + '&scenarioId2=' + scenarioId2).addTo(A.map);

				}
				else {

					var scenarioId = this.$('#scenario1').val();

					if(A.map.hasLayer(this.transitOverlays[scenarioId]))
			 			A.map.removeLayer(this.transitOverlays[scenarioId]);

					this.transitOverlays[scenarioId] = L.tileLayer('/tile/transit?z={z}&x={x}&y={y}&scenarioId=' + scenarioId).addTo(A.map);

				}
			}
			else {

				for(var id in this.transitOverlays){
					if(this.transitOverlays[id] && A.map.hasLayer(this.transitOverlays[id]))
						A.map.removeLayer(this.transitOverlays[id]);
				}

			}

			if(!this.surfaceId1 && (this.surfaceId2 || this.comparisonType != 'compare'))
				return;

			$('#results1').hide();
			$('#results2').hide();

			var minTime = this.timeSlider.getValue()[0] * 60;
			var timeLimit = this.timeSlider.getValue()[1] * 60;

			var showIso =  this.$('#showIso').prop('checked');
			var showPoints = false;//this.$('#showPoints').prop('checked');

			if(this.comparisonType == 'compare') {

				if(!this.surfaceId1 || !this.surfaceId2)
					return;

				if(A.map.tileOverlay && A.map.hasLayer(A.map.tileOverlay))
		  			A.map.removeLayer(A.map.tileOverlay);

				A.map.tileOverlay = L.tileLayer('/tile/surfaceComparison?z={z}&x={x}&y={y}&shapefileId=' + this.$('#shapefile').val() +
					'&minTime=' + minTime + '&timeLimit=' + timeLimit + '&surfaceId1=' + this.surfaceId1  + '&surfaceId2=' + this.surfaceId2 + '&showIso=' + showIso + '&showPoints=' +  showPoints, {}
					).addTo(A.map);

			}
			else {

				if(A.map.tileOverlay && A.map.hasLayer(A.map.tileOverlay))
		  			A.map.removeLayer(A.map.tileOverlay);

				A.map.tileOverlay = L.tileLayer('/tile/surface?z={z}&x={x}&y={y}&shapefileId=' + this.$('#shapefile').val() +
					'&minTime=' + minTime + '&timeLimit=' + timeLimit + '&showPoints=' + showPoints + '&showIso=' + showIso + '&surfaceId=' + this.surfaceId1, {

					}).addTo(A.map);

			}
		},

		downloadGis : function(evt) {
			var shapefileId = this.$('#shapefile').val();
			var attributeName = this.$('#shapefileColumn').val();
			var surfaceId = this.surfaceId1;
			var timeLimit = this.timeSlider.getValue()[1] * 60;

			window.location.href = '/gis/surface?shapefileId=' + shapefileId + '&surfaceId=' + surfaceId + '&timeLimit=' + timeLimit;

		},

		showSettings : function(evt) {

			this.$('#showSettings').hide();
			this.$('#querySettings').show();
		},

    /**
		 * Draw a chart using the given attribute contained within the given result, in the given div.
		 * chartIdx is an integer [1,2] specifying whether to render the first or second chart.
		 */
		drawChart : function(result, attribute, chartIdx, divSelector, chartHeight) {
			var _this = this;

			var color = result.properties.schema[attribute].style.color;

			var barChart = dc.barChart(divSelector);

			// pivot the data to make it ready for crossfilter
			var data = [];
			for (var i = 0; i < 120; i++) {
				data.push({minute: i, value: result.data[attribute].sums[i]});
			}

			var cfData = crossfilter(data);

			var minuteDimension = cfData.dimension(function (d) {
				return d.minute;
			});

			// aggregate to bins
			var aggregated = minuteDimension.group(function (minute) {
				return Math.floor(minute / 5) * 5;
			})
			.reduceSum(function (d) {
				return d.value;
			});

			var maxVal = aggregated.top(1)[0].value;

			if (maxVal > this.maxChartValue)
				this.maxChartValue = maxVal;

			barChart
				.width(400)
				// maximize data:ink ratio
				.gap(0.1)
				.height(chartHeight)
				.margins({top: 10, right: 20, bottom: 10, left: 40})
				.elasticY(false)
				.y(d3.scale.linear().domain([0, this.maxChartValue]))
				.dimension(minuteDimension)
				.ordinalColors([color])
				.xAxisLabel("Minutes")
				.yAxisLabel(result.properties.schema[attribute].label)
				.transitionDuration(0)
				.group(aggregated, result.properties.schema[attribute].label)
				.x(d3.scale.linear().domain([0, 120]))
				.renderHorizontalGridLines(true)
				.centerBar(false)
				.brushOn(false)
				// get the number of bins so that the bar width is correct in the histogram.
				// see https://github.com/dc-js/dc.js/issues/137
				.xUnits(function () { return aggregated.size(); })
				.xAxis().ticks(5).tickFormat(d3.format("d"));

				this['barChart' + chartIdx] = barChart;
				this['cfData' + chartIdx] = cfData;

				this.scaleBarCharts();

				dc.renderAll();
		},

		/*updateSummary : function() {

			this.$("#resultSummary").append("<tr><td></td></tr>");


		},*/

		scaleBarCharts : function() {

			if(this.barChart1)
				this.barChart1.y(d3.scale.linear().domain([0, this.maxChartValue]));

			if(this.barChart2)
				this.barChart2.y(d3.scale.linear().domain([0, this.maxChartValue]));
		},

		onRender : function() {

			if(A.map.tileOverlay && A.map.hasLayer(A.map.tileOverlay))
	  			A.map.removeLayer(A.map.tileOverlay);

			var _this = this;

			if(A.map.marker && A.map.hasLayer(A.map.marker))
	  			A.map.removeLayer(A.map.marker);

	  		A.map.on('click', this.onMapClick);

		},

		onMapClick : function(evt) {

	  		if(A.map.marker && A.map.hasLayer(A.map.marker))
	  			return;

	  		A.map.marker = new L.marker(evt.latlng, {draggable:'true'});

	  		A.map.marker.on('dragend', this.createSurface);

		    	A.map.addLayer(A.map.marker);

	    		this.createSurface();

		},

      	selectComparisonType : function(evt) {

			this.comparisonType = this.$('#scenarioComparison').val();

			if(this.comparisonType == 'compare') {
				$('#scenario2-controls').show();
			}
			else {
				$('#scenario2-controls').hide();
			}

			this.createSurface();
		}
	});

})(Analyst, jQuery);
