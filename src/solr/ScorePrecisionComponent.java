package solr;


import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.IndexReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.grouping.SearchGroup;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.InPlaceMergeSorter;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CursorMarkParams;
import org.apache.solr.common.params.GroupParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.MoreLikeThisParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.handler.component.MergeStrategy;
import org.apache.solr.handler.component.QueryComponent;
import org.apache.solr.handler.component.ResponseBuilder;
import org.apache.solr.handler.component.ShardDoc;
import org.apache.solr.handler.component.ShardRequest;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.ResultContext;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.CursorMark;
import org.apache.solr.search.DocIterator;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocListAndSet;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.Grouping;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.RankQuery;
import org.apache.solr.search.ReturnFields;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrIndexSearcher.QueryResult;
import org.apache.solr.search.SolrReturnFields;
import org.apache.solr.search.SortSpec;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.search.grouping.CommandHandler;
import org.apache.solr.search.grouping.GroupingSpecification;
import org.apache.solr.search.grouping.distributed.ShardRequestFactory;
import org.apache.solr.search.grouping.distributed.command.QueryCommand;
import org.apache.solr.search.grouping.distributed.command.SearchGroupsFieldCommand;
import org.apache.solr.search.grouping.distributed.command.TopGroupsFieldCommand;
import org.apache.solr.search.grouping.distributed.requestfactory.SearchGroupsRequestFactory;
import org.apache.solr.search.grouping.distributed.requestfactory.StoredFieldsShardRequestFactory;
import org.apache.solr.search.grouping.distributed.requestfactory.TopGroupsShardRequestFactory;
import org.apache.solr.search.grouping.distributed.shardresultserializer.SearchGroupsResultTransformer;
import org.apache.solr.search.grouping.distributed.shardresultserializer.TopGroupsResultTransformer;
import org.apache.solr.util.SolrPluginUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



public class ScorePrecisionComponent extends QueryComponent
{
  public static final String COMPONENT_NAME = "query";
  private static final Logger logger = LoggerFactory.getLogger(ScorePrecisionComponent.class);
  
  
//  This method overwrites the native prepare method of the query component 
  @Override
  public void prepare(ResponseBuilder rb) throws IOException
  {
	  logger.info("inside prepre of ScorePrecisionComponent");
    SolrQueryRequest req = rb.req;
    SolrParams params = req.getParams();
    if (!params.getBool(COMPONENT_NAME, true)) {
      return;
    }
    SolrQueryResponse rsp = rb.rsp;

    // Set field flags    
    ReturnFields returnFields = new SolrReturnFields( req );
    rsp.setReturnFields( returnFields );
    int flags = 0;
    if (returnFields.wantsScore()) {
      flags |= SolrIndexSearcher.GET_SCORES;
    }
    rb.setFieldFlags( flags );

    String defType = params.get(QueryParsing.DEFTYPE, QParserPlugin.DEFAULT_QTYPE);

    // get it from the response builder to give a different component a chance
    // to set it.
    String queryString = rb.getQueryString();
    if (queryString == null) {
      // this is the normal way it's set.
      queryString = params.get( CommonParams.Q );
      rb.setQueryString(queryString);
    }

    try {
      QParser parser = QParser.getParser(rb.getQueryString(), defType, req);
      Query q = parser.getQuery();
      if (q == null) {
        // normalize a null query to a query that matches nothing
        q = new BooleanQuery();        
      }

      rb.setQuery( q );

      String rankQueryString = rb.req.getParams().get(CommonParams.RQ);
      if(rankQueryString != null) {
        QParser rqparser = QParser.getParser(rankQueryString, defType, req);
        Query rq = rqparser.getQuery();
        if(rq instanceof RankQuery) {
          RankQuery rankQuery = (RankQuery)rq;
          rb.setRankQuery(rankQuery);
          MergeStrategy mergeStrategy = rankQuery.getMergeStrategy();
          if(mergeStrategy != null) {
            rb.addMergeStrategy(mergeStrategy);
            if(mergeStrategy.handlesMergeFields()) {
              rb.mergeFieldHandler = mergeStrategy;
            }
          }
        } else {
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,"rq parameter must be a RankQuery");
        }
      }

      rb.setSortSpec( parser.getSort(true) );
      rb.setQparser(parser);
      
      final String cursorStr = rb.req.getParams().get(CursorMarkParams.CURSOR_MARK_PARAM);
      if (null != cursorStr) {
        final CursorMark cursorMark = new CursorMark(rb.req.getSchema(),
                                                     rb.getSortSpec());
        cursorMark.parseSerializedTotem(cursorStr);
        rb.setCursorMark(cursorMark);
      }

      String[] fqs = req.getParams().getParams(CommonParams.FQ);
      if (fqs!=null && fqs.length!=0) {
        List<Query> filters = rb.getFilters();
        // if filters already exists, make a copy instead of modifying the original
        filters = filters == null ? new ArrayList<Query>(fqs.length) : new ArrayList<>(filters);
        for (String fq : fqs) {
          if (fq != null && fq.trim().length()!=0) {
            QParser fqp = QParser.getParser(fq, null, req);
            filters.add(fqp.getQuery());
          }
        }
        // only set the filters if they are not empty otherwise
        // fq=&someotherParam= will trigger all docs filter for every request 
        // if filter cache is disabled
        if (!filters.isEmpty()) {
          rb.setFilters( filters );
        }
      }
    } catch (SyntaxError e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
    }

    if (params.getBool(GroupParams.GROUP, false)) {
      prepareGrouping(rb);
    }
  }

  private void prepareGrouping(ResponseBuilder rb) throws IOException {

    SolrQueryRequest req = rb.req;
    SolrParams params = req.getParams();

    if (null != rb.getCursorMark()) {
      // It's hard to imagine, conceptually, what it would mean to combine
      // grouping with a cursor - so for now we just don't allow the combination at all
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Can not use Grouping with " + 
                              CursorMarkParams.CURSOR_MARK_PARAM);
    }
 
    SolrIndexSearcher.QueryCommand cmd = rb.getQueryCommand();
    SolrIndexSearcher searcher = rb.req.getSearcher();
    GroupingSpecification groupingSpec = new GroupingSpecification();
    rb.setGroupingSpec(groupingSpec);

    //TODO: move weighting of sort
    Sort groupSort = searcher.weightSort(cmd.getSort());
    if (groupSort == null) {
      groupSort = Sort.RELEVANCE;
    }

    // groupSort defaults to sort
    String groupSortStr = params.get(GroupParams.GROUP_SORT);
    //TODO: move weighting of sort
    Sort sortWithinGroup = groupSortStr == null ?  groupSort : searcher.weightSort(QueryParsing.parseSortSpec(groupSortStr, req).getSort());
    if (sortWithinGroup == null) {
      sortWithinGroup = Sort.RELEVANCE;
    }

    groupingSpec.setSortWithinGroup(sortWithinGroup);
    groupingSpec.setGroupSort(groupSort);

    String formatStr = params.get(GroupParams.GROUP_FORMAT, Grouping.Format.grouped.name());
    Grouping.Format responseFormat;
    try {
       responseFormat = Grouping.Format.valueOf(formatStr);
    } catch (IllegalArgumentException e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, String.format(Locale.ROOT, "Illegal %s parameter", GroupParams.GROUP_FORMAT));
    }
    groupingSpec.setResponseFormat(responseFormat);

    groupingSpec.setFields(params.getParams(GroupParams.GROUP_FIELD));
    groupingSpec.setQueries(params.getParams(GroupParams.GROUP_QUERY));
    groupingSpec.setFunctions(params.getParams(GroupParams.GROUP_FUNC));
    groupingSpec.setGroupOffset(params.getInt(GroupParams.GROUP_OFFSET, 0));
    groupingSpec.setGroupLimit(params.getInt(GroupParams.GROUP_LIMIT, 1));
    groupingSpec.setOffset(rb.getSortSpec().getOffset());
    groupingSpec.setLimit(rb.getSortSpec().getCount());
    groupingSpec.setIncludeGroupCount(params.getBool(GroupParams.GROUP_TOTAL_COUNT, false));
    groupingSpec.setMain(params.getBool(GroupParams.GROUP_MAIN, false));
    groupingSpec.setNeedScore((cmd.getFlags() & SolrIndexSearcher.GET_SCORES) != 0);
    groupingSpec.setTruncateGroups(params.getBool(GroupParams.GROUP_TRUNCATE, false));
  }



  /**
   * Actually run the query
   */
  @Override
  public void process(ResponseBuilder rb) throws IOException
  {
	  logger.info("inside process of ScorePrecisionComponent");
    SolrQueryRequest req = rb.req;
    SolrQueryResponse rsp = rb.rsp;
    SolrParams params = req.getParams();
    if (!params.getBool(COMPONENT_NAME, true)) {
      return;
    }
    SolrIndexSearcher searcher = req.getSearcher();

    if (rb.getQueryCommand().getOffset() < 0) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "'start' parameter cannot be negative");
    }

    // -1 as flag if not set.
    long timeAllowed = (long)params.getInt( CommonParams.TIME_ALLOWED, -1 );
    if (null != rb.getCursorMark() && 0 < timeAllowed) {
      // fundementally incompatible
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Can not search using both " + 
                              CursorMarkParams.CURSOR_MARK_PARAM + " and " + CommonParams.TIME_ALLOWED);
    }

    // Optional: This could also be implemented by the top-level searcher sending
    // a filter that lists the ids... that would be transparent to
    // the request handler, but would be more expensive (and would preserve score
    // too if desired).
    String ids = params.get(ShardParams.IDS);
    if (ids != null) {
      SchemaField idField = searcher.getSchema().getUniqueKeyField();
      List<String> idArr = StrUtils.splitSmart(ids, ",", true);
      int[] luceneIds = new int[idArr.size()];
      int docs = 0;
      for (int i=0; i<idArr.size(); i++) {
        int id = req.getSearcher().getFirstMatch(
                new Term(idField.getName(), idField.getType().toInternal(idArr.get(i))));
        if (id >= 0)
          luceneIds[docs++] = id;
      }

      DocListAndSet res = new DocListAndSet();
      res.docList = new DocSlice(0, docs, luceneIds, null, docs, 0);
      if (rb.isNeedDocSet()) {
        // TODO: create a cache for this!
        List<Query> queries = new ArrayList<>();
        queries.add(rb.getQuery());
        List<Query> filters = rb.getFilters();
        if (filters != null) queries.addAll(filters);
        res.docSet = searcher.getDocSet(queries);
      }
      rb.setResults(res);
      
      ResultContext ctx = new ResultContext();
      ctx.docs = rb.getResults().docList;
      ctx.query = null; // anything?
      rsp.add("response", ctx);
      return;
    }

    SolrIndexSearcher.QueryCommand cmd = rb.getQueryCommand();
    cmd.setTimeAllowed(timeAllowed);
    SolrIndexSearcher.QueryResult result = new SolrIndexSearcher.QueryResult();

    //
    // grouping / field collapsing
    //
    GroupingSpecification groupingSpec = rb.getGroupingSpec();
    if (groupingSpec != null) {
      try {
        boolean needScores = (cmd.getFlags() & SolrIndexSearcher.GET_SCORES) != 0;
        if (params.getBool(GroupParams.GROUP_DISTRIBUTED_FIRST, false)) {
          CommandHandler.Builder topsGroupsActionBuilder = new CommandHandler.Builder()
              .setQueryCommand(cmd)
              .setNeedDocSet(false) // Order matters here
              .setIncludeHitCount(true)
              .setSearcher(searcher);

          for (String field : groupingSpec.getFields()) {
            topsGroupsActionBuilder.addCommandField(new SearchGroupsFieldCommand.Builder()
                .setField(searcher.getSchema().getField(field))
                .setGroupSort(groupingSpec.getGroupSort())
                .setTopNGroups(cmd.getOffset() + cmd.getLen())
                .setIncludeGroupCount(groupingSpec.isIncludeGroupCount())
                .build()
            );
          }

          CommandHandler commandHandler = topsGroupsActionBuilder.build();
          commandHandler.execute();
          SearchGroupsResultTransformer serializer = new SearchGroupsResultTransformer(searcher);
          rsp.add("firstPhase", commandHandler.processResult(result, serializer));
          rsp.add("totalHitCount", commandHandler.getTotalHitCount());
          rb.setResult(result);
          return;
        } else if (params.getBool(GroupParams.GROUP_DISTRIBUTED_SECOND, false)) {
          CommandHandler.Builder secondPhaseBuilder = new CommandHandler.Builder()
              .setQueryCommand(cmd)
              .setTruncateGroups(groupingSpec.isTruncateGroups() && groupingSpec.getFields().length > 0)
              .setSearcher(searcher);

          for (String field : groupingSpec.getFields()) {
            String[] topGroupsParam = params.getParams(GroupParams.GROUP_DISTRIBUTED_TOPGROUPS_PREFIX + field);
            if (topGroupsParam == null) {
              topGroupsParam = new String[0];
            }

            List<SearchGroup<BytesRef>> topGroups = new ArrayList<>(topGroupsParam.length);
            for (String topGroup : topGroupsParam) {
              SearchGroup<BytesRef> searchGroup = new SearchGroup<>();
              if (!topGroup.equals(TopGroupsShardRequestFactory.GROUP_NULL_VALUE)) {
                searchGroup.groupValue = new BytesRef(searcher.getSchema().getField(field).getType().readableToIndexed(topGroup));
              }
              topGroups.add(searchGroup);
            }

            secondPhaseBuilder.addCommandField(
                new TopGroupsFieldCommand.Builder()
                    .setField(searcher.getSchema().getField(field))
                    .setGroupSort(groupingSpec.getGroupSort())
                    .setSortWithinGroup(groupingSpec.getSortWithinGroup())
                    .setFirstPhaseGroups(topGroups)
                    .setMaxDocPerGroup(groupingSpec.getGroupOffset() + groupingSpec.getGroupLimit())
                    .setNeedScores(needScores)
                    .setNeedMaxScore(needScores)
                    .build()
            );
          }

          for (String query : groupingSpec.getQueries()) {
            secondPhaseBuilder.addCommandField(new QueryCommand.Builder()
                .setDocsToCollect(groupingSpec.getOffset() + groupingSpec.getLimit())
                .setSort(groupingSpec.getGroupSort())
                .setQuery(query, rb.req)
                .setDocSet(searcher)
                .build()
            );
          }

          CommandHandler commandHandler = secondPhaseBuilder.build();
          commandHandler.execute();
          TopGroupsResultTransformer serializer = new TopGroupsResultTransformer(rb);
          rsp.add("secondPhase", commandHandler.processResult(result, serializer));
          rb.setResult(result);
          return;
        }

        int maxDocsPercentageToCache = params.getInt(GroupParams.GROUP_CACHE_PERCENTAGE, 0);
        boolean cacheSecondPassSearch = maxDocsPercentageToCache >= 1 && maxDocsPercentageToCache <= 100;
        Grouping.TotalCount defaultTotalCount = groupingSpec.isIncludeGroupCount() ?
            Grouping.TotalCount.grouped : Grouping.TotalCount.ungrouped;
        int limitDefault = cmd.getLen(); // this is normally from "rows"
        Grouping grouping =
            new Grouping(searcher, result, cmd, cacheSecondPassSearch, maxDocsPercentageToCache, groupingSpec.isMain());
        grouping.setSort(groupingSpec.getGroupSort())
            .setGroupSort(groupingSpec.getSortWithinGroup())
            .setDefaultFormat(groupingSpec.getResponseFormat())
            .setLimitDefault(limitDefault)
            .setDefaultTotalCount(defaultTotalCount)
            .setDocsPerGroupDefault(groupingSpec.getGroupLimit())
            .setGroupOffsetDefault(groupingSpec.getGroupOffset())
            .setGetGroupedDocSet(groupingSpec.isTruncateGroups());

        if (groupingSpec.getFields() != null) {
          for (String field : groupingSpec.getFields()) {
            grouping.addFieldCommand(field, rb.req);
          }
        }

        if (groupingSpec.getFunctions() != null) {
          for (String groupByStr : groupingSpec.getFunctions()) {
            grouping.addFunctionCommand(groupByStr, rb.req);
          }
        }

        if (groupingSpec.getQueries() != null) {
          for (String groupByStr : groupingSpec.getQueries()) {
            grouping.addQueryCommand(groupByStr, rb.req);
          }
        }

        if (rb.doHighlights || rb.isDebug() || params.getBool(MoreLikeThisParams.MLT, false)) {
          // we need a single list of the returned docs
          cmd.setFlags(SolrIndexSearcher.GET_DOCLIST);
        }

        grouping.execute();
        if (grouping.isSignalCacheWarning()) {
          rsp.add(
              "cacheWarning",
              String.format(Locale.ROOT, "Cache limit of %d percent relative to maxdoc has exceeded. Please increase cache size or disable caching.", maxDocsPercentageToCache)
          );
        }
        rb.setResult(result);

        if (grouping.mainResult != null) {
          ResultContext ctx = new ResultContext();
          ctx.docs = grouping.mainResult;
          ctx.query = null; // TODO? add the query?
          rsp.add("response", ctx);
          rsp.getToLog().add("hits", grouping.mainResult.matches());
        } else if (!grouping.getCommands().isEmpty()) { // Can never be empty since grouping.execute() checks for this.
          rsp.add("grouped", result.groupedResults);
          rsp.getToLog().add("hits", grouping.getCommands().get(0).getMatches());
        }
        return;
      } catch (SyntaxError e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
      }
    }

    // normal search result
    searcher.search(result,cmd);
//    sortResults(result,rb,searcher);
    try{
    	new SortResults().sortSolrResults(result,rb,searcher);
    }catch(Exception e){
    	logger.error("Error Occur while applying precision,So returning orignal results");
    }
    rb.setResult( result );

    ResultContext ctx = new ResultContext();
    ctx.docs = rb.getResults().docList;
    ctx.query = rb.getQuery();
    rsp.add("response", ctx);
    rsp.getToLog().add("hits", rb.getResults().docList.matches());

    if ( ! rb.req.getParams().getBool(ShardParams.IS_SHARD,false) ) {
      if (null != rb.getNextCursorMark()) {
        rb.rsp.add(CursorMarkParams.CURSOR_MARK_NEXT, 
                   rb.getNextCursorMark().getSerializedTotem());
      }
    }

    if(rb.mergeFieldHandler != null) {
      rb.mergeFieldHandler.handleMergeFields(rb, searcher);
    } else {
      doFieldSortValues(rb, searcher);
    }

    doPrefetch(rb);
  }

  

protected void doFieldSortValues(ResponseBuilder rb, SolrIndexSearcher searcher) throws IOException
  {
    SolrQueryRequest req = rb.req;
    SolrQueryResponse rsp = rb.rsp;
    // The query cache doesn't currently store sort field values, and SolrIndexSearcher doesn't
    // currently have an option to return sort field values.  Because of this, we
    // take the documents given and re-derive the sort values.
    //
    // TODO: See SOLR-5595
    boolean fsv = req.getParams().getBool(ResponseBuilder.FIELD_SORT_VALUES,false);
    if(fsv){
      NamedList<Object[]> sortVals = new NamedList<>(); // order is important for the sort fields
      IndexReaderContext topReaderContext = searcher.getTopReaderContext();
      List<AtomicReaderContext> leaves = topReaderContext.leaves();
      AtomicReaderContext currentLeaf = null;
      if (leaves.size()==1) {
        // if there is a single segment, use that subReader and avoid looking up each time
        currentLeaf = leaves.get(0);
        leaves=null;
      }

      DocList docList = rb.getResults().docList;

      // sort ids from lowest to highest so we can access them in order
      int nDocs = docList.size();
      final long[] sortedIds = new long[nDocs];
      final float[] scores = new float[nDocs]; // doc scores, parallel to sortedIds
      DocList docs = rb.getResults().docList;
      DocIterator it = docs.iterator();
      for (int i=0; i<nDocs; i++) {
        sortedIds[i] = (((long)it.nextDoc()) << 32) | i;
        scores[i] = docs.hasScores() ? it.score() : Float.NaN;
      }

      // sort ids and scores together
      new InPlaceMergeSorter() {
        @Override
        protected void swap(int i, int j) {
          long tmpId = sortedIds[i];
          float tmpScore = scores[i];
          sortedIds[i] = sortedIds[j];
          scores[i] = scores[j];
          sortedIds[j] = tmpId;
          scores[j] = tmpScore;
        }

        @Override
        protected int compare(int i, int j) {
          return Long.compare(sortedIds[i], sortedIds[j]);
        }
      }.sort(0, sortedIds.length);

      SortSpec sortSpec = rb.getSortSpec();
      Sort sort = searcher.weightSort(sortSpec.getSort());
      SortField[] sortFields = sort==null ? new SortField[]{SortField.FIELD_SCORE} : sort.getSort();
      List<SchemaField> schemaFields = sortSpec.getSchemaFields();

      for (int fld = 0; fld < schemaFields.size(); fld++) {
        SchemaField schemaField = schemaFields.get(fld);
        FieldType ft = null == schemaField? null : schemaField.getType();
        SortField sortField = sortFields[fld];

        SortField.Type type = sortField.getType();
        // :TODO: would be simpler to always serialize every position of SortField[]
        if (type==SortField.Type.SCORE || type==SortField.Type.DOC) continue;

        @SuppressWarnings("rawtypes")
		FieldComparator comparator = null;
        Object[] vals = new Object[nDocs];

        int lastIdx = -1;
        int idx = 0;

        for (int i = 0; i < sortedIds.length; ++i) {
          long idAndPos = sortedIds[i];
          float score = scores[i];
          int doc = (int)(idAndPos >>> 32);
          int position = (int)idAndPos;

          if (leaves != null) {
            idx = ReaderUtil.subIndex(doc, leaves);
            currentLeaf = leaves.get(idx);
            if (idx != lastIdx) {
              // we switched segments.  invalidate comparator.
              comparator = null;
            }
          }

          if (comparator == null) {
            comparator = sortField.getComparator(1,0);
            comparator = comparator.setNextReader(currentLeaf);
          }

          doc -= currentLeaf.docBase;  // adjust for what segment this is in
          comparator.setScorer(new FakeScorer(doc, score));
          comparator.copy(0, doc);
          Object val = comparator.value(0);
          if (null != ft) val = ft.marshalSortValue(val); 
          vals[position] = val;
        }

        sortVals.add(sortField.getField(), vals);
      }

      rsp.add("sort_values", sortVals);
    }
  }



protected void doPrefetch(ResponseBuilder rb) throws IOException
  {
    SolrQueryRequest req = rb.req;
    SolrQueryResponse rsp = rb.rsp;
    //pre-fetch returned documents
    if (!req.getParams().getBool(ShardParams.IS_SHARD,false) && rb.getResults().docList != null && rb.getResults().docList.size()<=50) {
      SolrPluginUtils.optimizePreFetchDocs(rb, rb.getResults().docList, rb.getQuery(), req, rsp);
    }
  }

  @Override  
  public int distributedProcess(ResponseBuilder rb) throws IOException {
    if (rb.grouping()) {
      return groupedDistributedProcess(rb);
    } else {
      return regularDistributedProcess(rb);
    }
  }

  private int groupedDistributedProcess(ResponseBuilder rb) {
    int nextStage = ResponseBuilder.STAGE_DONE;
    ShardRequestFactory shardRequestFactory = null;

    if (rb.stage < ResponseBuilder.STAGE_PARSE_QUERY) {
      nextStage = ResponseBuilder.STAGE_PARSE_QUERY;
    } else if (rb.stage == ResponseBuilder.STAGE_PARSE_QUERY) {
      createDistributedIdf(rb);
      nextStage = ResponseBuilder.STAGE_TOP_GROUPS;
    } else if (rb.stage < ResponseBuilder.STAGE_TOP_GROUPS) {
      nextStage = ResponseBuilder.STAGE_TOP_GROUPS;
    } else if (rb.stage == ResponseBuilder.STAGE_TOP_GROUPS) {
      shardRequestFactory = new SearchGroupsRequestFactory();
      nextStage = ResponseBuilder.STAGE_EXECUTE_QUERY;
    } else if (rb.stage < ResponseBuilder.STAGE_EXECUTE_QUERY) {
      nextStage = ResponseBuilder.STAGE_EXECUTE_QUERY;
    } else if (rb.stage == ResponseBuilder.STAGE_EXECUTE_QUERY) {
      shardRequestFactory = new TopGroupsShardRequestFactory();
      nextStage = ResponseBuilder.STAGE_GET_FIELDS;
    } else if (rb.stage < ResponseBuilder.STAGE_GET_FIELDS) {
      nextStage = ResponseBuilder.STAGE_GET_FIELDS;
    } else if (rb.stage == ResponseBuilder.STAGE_GET_FIELDS) {
      shardRequestFactory = new StoredFieldsShardRequestFactory();
      nextStage = ResponseBuilder.STAGE_DONE;
    }

    if (shardRequestFactory != null) {
      for (ShardRequest shardRequest : shardRequestFactory.constructRequest(rb)) {
        rb.addRequest(this, shardRequest);
      }
    }
    return nextStage;
  }

  private int regularDistributedProcess(ResponseBuilder rb) {
    if (rb.stage < ResponseBuilder.STAGE_PARSE_QUERY)
      return ResponseBuilder.STAGE_PARSE_QUERY;
    if (rb.stage == ResponseBuilder.STAGE_PARSE_QUERY) {
      createDistributedIdf(rb);
      return ResponseBuilder.STAGE_EXECUTE_QUERY;
    }
    if (rb.stage < ResponseBuilder.STAGE_EXECUTE_QUERY) return ResponseBuilder.STAGE_EXECUTE_QUERY;
    if (rb.stage == ResponseBuilder.STAGE_EXECUTE_QUERY) {
      createMainQuery(rb);
      return ResponseBuilder.STAGE_GET_FIELDS;
    }
    if (rb.stage < ResponseBuilder.STAGE_GET_FIELDS) return ResponseBuilder.STAGE_GET_FIELDS;
    if (rb.stage == ResponseBuilder.STAGE_GET_FIELDS && !rb.onePassDistributedQuery) {
      createRetrieveDocs(rb);
      return ResponseBuilder.STAGE_DONE;
    }
    return ResponseBuilder.STAGE_DONE;
  }




 

  private void createDistributedIdf(ResponseBuilder rb) {
    // TODO
  }

  private void createMainQuery(ResponseBuilder rb) {
    ShardRequest sreq = new ShardRequest();
    sreq.purpose = ShardRequest.PURPOSE_GET_TOP_IDS;

    String keyFieldName = rb.req.getSchema().getUniqueKeyField().getName();

    // one-pass algorithm if only id and score fields are requested, but not if fl=score since that's the same as fl=*,score
    ReturnFields fields = rb.rsp.getReturnFields();

    if(fields != null && fields.wantsField(keyFieldName)
        && fields.getRequestedFieldNames() != null && Arrays.asList(keyFieldName, "score").containsAll(fields.getRequestedFieldNames())) {
      sreq.purpose |= ShardRequest.PURPOSE_GET_FIELDS;
      rb.onePassDistributedQuery = true;
    }

    sreq.params = new ModifiableSolrParams(rb.req.getParams());
    // TODO: base on current params or original params?

    // don't pass through any shards param
    sreq.params.remove(ShardParams.SHARDS);

    // set the start (offset) to 0 for each shard request so we can properly merge
    // results from the start.
    if(rb.shards_start > -1) {
      // if the client set shards.start set this explicitly
      sreq.params.set(CommonParams.START,rb.shards_start);
    } else {
      sreq.params.set(CommonParams.START, "0");
    }
    // TODO: should we even use the SortSpec?  That's obtained from the QParser, and
    // perhaps we shouldn't attempt to parse the query at this level?
    // Alternate Idea: instead of specifying all these things at the upper level,
    // we could just specify that this is a shard request.
    if(rb.shards_rows > -1) {
      // if the client set shards.rows set this explicity
      sreq.params.set(CommonParams.ROWS,rb.shards_rows);
    } else {
      sreq.params.set(CommonParams.ROWS, rb.getSortSpec().getOffset() + rb.getSortSpec().getCount());
    }

    // in this first phase, request only the unique key field
    // and any fields needed for merging.
    sreq.params.set(ResponseBuilder.FIELD_SORT_VALUES,"true");

    if ( (rb.getFieldFlags() & SolrIndexSearcher.GET_SCORES)!=0 || rb.getSortSpec().includesScore()) {
      sreq.params.set(CommonParams.FL, keyFieldName + ",score");
    } else {
      sreq.params.set(CommonParams.FL, keyFieldName);
    }

    rb.addRequest(this, sreq);
  }






  /**
   * Inspects the state of the {@link ResponseBuilder} and populates the next 
   * {@link ResponseBuilder#setNextCursorMark} as appropriate based on the merged 
   * sort values from individual shards
   *
   * @param rb A <code>ResponseBuilder</code> that already contains merged 
   *           <code>ShardDocs</code> in <code>resultIds</code>, may or may not be 
   *           part of a Cursor based request (method will NOOP if not needed)
   */


  private void createRetrieveDocs(ResponseBuilder rb) {

    // TODO: in a system with nTiers > 2, we could be passed "ids" here
    // unless those requests always go to the final destination shard

    // for each shard, collect the documents for that shard.
    HashMap<String, Collection<ShardDoc>> shardMap = new HashMap<>();
    for (ShardDoc sdoc : rb.resultIds.values()) {
      Collection<ShardDoc> shardDocs = shardMap.get(sdoc.shard);
      if (shardDocs == null) {
        shardDocs = new ArrayList<>();
        shardMap.put(sdoc.shard, shardDocs);
      }
      shardDocs.add(sdoc);
    }

    SchemaField uniqueField = rb.req.getSchema().getUniqueKeyField();

    // Now create a request for each shard to retrieve the stored fields
    for (Collection<ShardDoc> shardDocs : shardMap.values()) {
      ShardRequest sreq = new ShardRequest();
      sreq.purpose = ShardRequest.PURPOSE_GET_FIELDS;

      sreq.shards = new String[] {shardDocs.iterator().next().shard};

      sreq.params = new ModifiableSolrParams();

      // add original params
      sreq.params.add( rb.req.getParams());

      // no need for a sort, we already have order
      sreq.params.remove(CommonParams.SORT);
      sreq.params.remove(CursorMarkParams.CURSOR_MARK_PARAM);

      // we already have the field sort values
      sreq.params.remove(ResponseBuilder.FIELD_SORT_VALUES);

      if(!rb.rsp.getReturnFields().wantsField(uniqueField.getName())) {
        sreq.params.add(CommonParams.FL, uniqueField.getName());
      }
    
      ArrayList<String> ids = new ArrayList<>(shardDocs.size());
      for (ShardDoc shardDoc : shardDocs) {
        // TODO: depending on the type, we may need more tha a simple toString()?
        ids.add(shardDoc.id.toString());
      }
      sreq.params.add(ShardParams.IDS, StrUtils.join(ids, ','));

      rb.addRequest(this, sreq);
    }

  }



  /////////////////////////////////////////////
  ///  SolrInfoMBean
  ////////////////////////////////////////////

  @Override
  public String getDescription() {
    return "query";
  }

  @Override
  public String getSource() {
    return "$URL: https://svn.apache.org/repos/asf/lucene/dev/branches/lucene_solr_4_9/solr/core/src/java/org/apache/solr/handler/component/QueryComponent.java $";
  }

  @Override
  public URL[] getDocs() {
    return null;
  }

  /**
   * Fake scorer for a single document
   *
   * TODO: when SOLR-5595 is fixed, this wont be needed, as we dont need to recompute sort values here from the comparator
   */
  private static class FakeScorer extends Scorer {
    final int docid;
    final float score;

    FakeScorer(int docid, float score) {
      super(null);
      this.docid = docid;
      this.score = score;
    }

    @Override
    public int docID() {
      return docid;
    }

    @Override
    public float score() throws IOException {
      return score;
    }

    @Override
    public int freq() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int nextDoc() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public int advance(int target) throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public long cost() {
      return 1;
    }

    @Override
    public Weight getWeight() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<ChildScorer> getChildren() {
      throw new UnsupportedOperationException();
    }
  }
  
  
	public static boolean isBlank(String str) {
		int strLen;
		if ((str == null) || ((strLen = str.length()) == 0))
			return true;
		
		for (int i = 0; i < strLen; ++i) {
			if (!(Character.isWhitespace(str.charAt(i)))) {
				return false;
			}
		}
		return true;
	}
}