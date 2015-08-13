$(document).ready(function(){
    var hash = window.location.href.replace(/^.*?#/,'').split('/');
    var host = hash[1];
    var tab = hash[2];
    
    var stateLabel = {
       'Starting' : 'label-primary',
       'Started'  : 'label-success',
       'Stopped'  : 'label-default',
       'Failed'   : 'label-danger',
    };
    
    $.get("http://" + host + ":8077/lifecycle", function(lifecycle) {
        $('#lifecycle-state').html(lifecycle.state);
        $('#lifecycle-reason').html(lifecycle.reason);
        $('#lifecycle-state').addClass(stateLabel[lifecycle.state]);
    });
});
