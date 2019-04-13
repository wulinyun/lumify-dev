
define([
    'util/range',
    'testutils/selectionUtils'
], function(r, selectionUtils) {

    describe('rangeUtils', function() {

        describe('createSnippetFromNode', function() {
            beforeEach(function() {
                $(document.body).empty();
            })

            it('should expand node by n words', function() {
                var node = $('<div>This is <span>a</span> sentence ' +
                             'with some more words than we\'ll capture</div>').appendTo(document.body);

                expect(r.createSnippetFromNode(node.find('span')[0])).to.equal(
                    'This is <span class="selection">a</span> sentence with some more...'
                )
            })

            it('should expand node by n words even with other spans', function() {
                var node = $('<div>This is <span class="t">a Phone</span> <span>sentence</span></div>')
                    .appendTo(document.body);

                expect(r.createSnippetFromNode(node.find('span.t')[0])).to.equal(
                    'This is <span class="selection">a Phone</span> sentence'
                )
            })

            it('should not include text outside of the div', function() {
                var node = $('<div>More</div><div class="text">This is <span>a</span> sentence</div><p>hahah</p>')
                    .appendTo(document.body);

                expect(r.createSnippetFromNode(node.find('span')[0], undefined, node.filter('.text')[0])).to.equal(
                    'This is <span class="selection">a</span> sentence'
                )

                expect(r.createSnippetFromNode(node.find('span')[0], undefined, node.filter('.text')[0])).to.equal(
                    'This is <span class="selection">a</span> sentence'
                )
            })

        })

        describe('createSnippetFromRange', function() {
            beforeEach(function() {
                $(document.body).empty();
            })

            it('should expand node by n words', function() {
                var node = $('<div></div>').appendTo(document.body),
                    range = selectionUtils.createRange(node, 'This is a [test] of the emergency broadcast system');

                expect(r.createSnippetFromRange(range)).to.equal(
                    'This is a <span class="selection">test</span> of the emergency broadcast...'
                )
            })
        })

        describe('expandRangeByWords', function() {
            var node, outerNode;

            before(function() {
                $(document.body).empty();
            })

            beforeEach(function() {
                outerNode = $('<div><div></div></div>')
                    .appendTo(document.body);
                node = outerNode.children('div');
            })

            afterEach(function() {
                outerNode.remove();
            })

            it('should expand range by n words with only textnodes', function() {

                var range = selectionUtils.createRange(node, 'This is a [test] of the emergency broadcast system');

                expect(range.startContainer).to.equal(node[0].childNodes[0]);
                expect(range.startOffset).to.equal(10);
                expect(range.endContainer).to.equal(node[0].childNodes[0]);
                expect(range.endOffset).to.equal(14);

                var expanded = r.expandRangeByWords(range, 1);

                expect(expanded.startContainer).to.equal(node[0].childNodes[0]);
                expect(expanded.startOffset).to.equal(8);
                expect(expanded.endContainer).to.equal(node[0].childNodes[0]);
                expect(expanded.endOffset).to.equal(17);

            });

            it('should expand range with two words', function() {
                var range = selectionUtils.createRange(node, 'Autism affects [information] processing in the'),
                    parts = {};
                expect(r.expandRangeByWords(range, 2, parts).toString())
                    .to.equal('Autism affects information processing in');
                expect(parts.before).to.equal('Autism affects ');
                expect(parts.after).to.equal(' processing in');
            });

            it('should expand range with more words than it has before', function() {
                var range = selectionUtils.createRange(node, 'Autism [affects information] processing in the');
                expect(r.expandRangeByWords(range, 2).toString()).to.equal('Autism affects information processing in');
            });

            it('should expand range with more words than it has after', function() {
                var range = selectionUtils.createRange(node, 'Autism affects [information processing in] the');
                expect(r.expandRangeByWords(range, 2).toString())
                    .to.equal('Autism affects information processing in the');
            });

            it('should expand range to nearby span', function() {
                var range = selectionUtils.createRange(
                    node,
                   'That <span>Autism</span> [affects] information processing in the'
                );
                expect(r.expandRangeByWords(range, 2).toString())
                    .to.equal('That Autism affects information processing');
            })

            it('should expand range to nearby spans', function() {
                var range = selectionUtils.createRange(
                        node,
                        '<span>That </span><span>Autism</span> [affects] <span>information processing in</span> the'
                    ),
                    parts = {};

                expect(r.expandRangeByWords(range, 2, parts).toString())
                    .to.equal('That Autism affects information processing');
                expect(parts.before).to.equal('That Autism ');
                expect(parts.after).to.equal(' information processing');

                range = selectionUtils.createRange(
                    node,
                    '<span>That</span> <span>Autism</span> [affects] information processing in the'
                );
                expect(r.expandRangeByWords(range, 2).toString())
                    .to.equal('That Autism affects information processing');

                range = selectionUtils.createRange(
                    node,
                    'They<span> say</span> that the <span>Autism</span> ' +
                    '[affects] <span>information processing in</span> the'
                );
                expect(r.expandRangeByWords(range, 5).toString())
                    .to.equal('They say that the Autism affects information processing in the');
            })
        });

    })
});
